import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

import '../../core/config/server_config.dart';
import '../../core/discovery/lan_scanner.dart';
import '../../core/providers.dart';

/// 启动页：手机不会自动知道电脑的 IP（校园网还会变），
/// 所以这里提供「一键扫描」+「手动输入」+「扫码」三条路。
class ConnectPage extends ConsumerStatefulWidget {
  const ConnectPage({super.key});

  @override
  ConsumerState<ConnectPage> createState() => _ConnectPageState();
}

class _ConnectPageState extends ConsumerState<ConnectPage> {
  final _hostController = TextEditingController();
  final _portController = TextEditingController(text: ServerConfig.defaultPort.toString());
  final _found = <ServerCandidate>[];

  StreamSubscription<Object>? _sub;
  DiscoveryProgress? _progress;
  String? _status;
  bool _scanning = false;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _trySaved());
  }

  @override
  void dispose() {
    _sub?.cancel();
    _hostController.dispose();
    _portController.dispose();
    super.dispose();
  }

  /// 冷启动先试上次的地址：同一网段下大概率还能用，省一次扫描。
  Future<void> _trySaved() async {
    final saved = ref.read(serverConfigServiceProvider).load();
    if (saved == null || saved.isEmpty) return;
    setState(() {
      _busy = true;
      _status = '正在探测上次的地址 ' + saved.toString() + ' …';
    });
    final hit = await ref.read(lanScannerProvider).trySaved(saved);
    if (!mounted) return;
    setState(() {
      _busy = false;
      _status = hit == null
          ? '上次的地址 ' + saved.toString() + ' 已失效，点「一键扫描」重新找'
          : '上次的地址仍然可用';
    });
    if (hit != null) _use(hit);
  }

  void _startScan() {
    _sub?.cancel();
    setState(() {
      _scanning = true;
      _found.clear();
      _progress = const DiscoveryProgress(phase: '准备中…');
      _status = null;
    });
    _sub = ref.read(lanScannerProvider).discover().listen(
      (event) {
        if (!mounted) return;
        if (event is DiscoveryProgress) {
          setState(() => _progress = event);
        } else if (event is ServerCandidate) {
          setState(() {
            _found.removeWhere((c) => c.config.origin == event.config.origin);
            _found.insert(0, event);
          });
        }
      },
      onError: (Object e) {
        if (!mounted) return;
        setState(() {
          _scanning = false;
          _status = '扫描出错：' + e.toString();
        });
      },
      onDone: () {
        if (!mounted) return;
        setState(() {
          _scanning = false;
          if (_found.isEmpty) {
            _status = '没扫到服务器。确认：① 后端已启动；② 手机和电脑同一个 WiFi；'
                '③ 防火墙放行 8080（校园网若开了 AP 隔离，就用手机热点让电脑连上）。';
          }
        });
      },
    );
  }

  void _stopScan() {
    _sub?.cancel();
    setState(() {
      _scanning = false;
      _progress = null;
      _status = '已停止扫描';
    });
  }

  Future<void> _connectManual() async {
    final host = _hostController.text.trim();
    final port = int.tryParse(_portController.text.trim()) ?? ServerConfig.defaultPort;
    if (host.isEmpty) {
      setState(() => _status = '先填服务器 IP');
      return;
    }
    setState(() {
      _busy = true;
      _status = '正在连接 ' + host + ':' + port.toString() + ' …';
    });
    final hit = await ref.read(lanScannerProvider).tryManual(host, port);
    if (!mounted) return;
    setState(() {
      _busy = false;
      _status = hit == null
          ? '连不上 ' + host + ':' + port.toString() + '，检查后端与网络'
          : null;
    });
    if (hit != null) _use(hit);
  }

  Future<void> _use(ServerCandidate candidate) async {
    await ref.read(activeServerProvider.notifier).use(candidate.config);
    if (!mounted) return;
    setState(() => _status = '已连接 ' + candidate.config.toString());
    _sub?.cancel();
    setState(() => _scanning = false);
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text('已连接 ' + candidate.config.origin)),
    );
  }

  Future<void> _scanQr() async {
    final value = await showDialog<String>(
      context: context,
      builder: (_) => const _QrScanDialog(),
    );
    if (value == null || value.isEmpty) return;
    final parsed = _parseQr(value);
    if (parsed == null) {
      setState(() => _status = '二维码内容看不懂：' + value);
      return;
    }
    _hostController.text = parsed.host;
    _portController.text = parsed.port.toString();
    await _connectManual();
  }

  static ServerConfig? _parseQr(String raw) {
    var text = raw.trim();
    if (text.isEmpty) return null;
    if (text.startsWith('{')) {
      final host = RegExp('"host"\\s*:\\s*"([^"]+)"').firstMatch(text)?.group(1);
      final port = RegExp('"port"\\s*:\\s*(\\d+)').firstMatch(text)?.group(1);
      if (host == null) return null;
      return ServerConfig(
        host: host,
        port: int.tryParse(port ?? '') ?? ServerConfig.defaultPort,
      );
    }
    text = text.replaceFirst(RegExp('^https?://'), '');
    final parts = text.split(':');
    final host = parts.first.split('/').first;
    if (host.isEmpty) return null;
    final port = parts.length > 1 ? int.tryParse(parts[1].split('/').first) : null;
    return ServerConfig(host: host, port: port ?? ServerConfig.defaultPort);
  }

  @override
  Widget build(BuildContext context) {
    final history = ref.watch(serverConfigServiceProvider).loadHistory();
    return Scaffold(
      appBar: AppBar(
        title: const Text('连接服务器'),
        actions: [
          IconButton(
            onPressed: _scanQr,
            icon: const Icon(Icons.qr_code_scanner),
            tooltip: '扫电脑上的二维码',
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _hero(),
          const SizedBox(height: 16),
          _scanCard(),
          if (_found.isNotEmpty) ...[
            const SizedBox(height: 16),
            _foundCard(),
          ],
          const SizedBox(height: 16),
          _manualCard(),
          if (history.isNotEmpty) ...[
            const SizedBox(height: 16),
            _historyCard(history),
          ],
        ],
      ),
    );
  }

  Widget _hero() => Card(
        color: Theme.of(context).colorScheme.primaryContainer,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: const [
              Text('校园二手 · 手机端', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
              SizedBox(height: 8),
              Text('手机和电脑要在同一个局域网。后端默认端口 8080，'
                  '校园网 IP 会变，所以每次换网络都点一下「一键扫描」。'),
            ],
          ),
        ),
      );

  Widget _scanCard() => Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  const Expanded(
                    child: Text('一键扫描', style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
                  ),
                  if (_scanning)
                    TextButton.icon(
                      onPressed: _stopScan,
                      icon: const Icon(Icons.stop_circle_outlined),
                      label: const Text('停止'),
                    )
                  else
                    FilledButton.icon(
                      onPressed: _busy ? null : _startScan,
                      icon: const Icon(Icons.wifi_find),
                      label: const Text('开始扫描'),
                    ),
                ],
              ),
              if (_progress != null) ...[
                const SizedBox(height: 12),
                LinearProgressIndicator(value: _progress!.total > 0 ? _progress!.ratio : null),
                const SizedBox(height: 8),
                Text(
                  _progress!.phase +
                      (_progress!.total > 0
                          ? '  ' + _progress!.scanned.toString() + '/' + _progress!.total.toString()
                          : ''),
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ],
              if (_status != null) ...[
                const SizedBox(height: 12),
                Text(_status!, style: const TextStyle(color: Colors.redAccent)),
              ],
            ],
          ),
        ),
      );

  Widget _foundCard() => Card(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Padding(
              padding: EdgeInsets.fromLTRB(16, 16, 16, 8),
              child: Text('发现以下服务器', style: TextStyle(fontWeight: FontWeight.bold)),
            ),
            for (final c in _found)
              ListTile(
                leading: const Icon(Icons.dns_outlined),
                title: Text(c.config.origin),
                subtitle: Text(c.methodLabel + ' · ' + c.note),
                trailing: const Icon(Icons.chevron_right),
                onTap: () => _use(c),
              ),
          ],
        ),
      );

  Widget _manualCard() => Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('手动输入', style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    flex: 3,
                    child: TextField(
                      controller: _hostController,
                      keyboardType: TextInputType.text,
                      decoration: const InputDecoration(
                        labelText: '服务器 IP',
                        hintText: '例如 10.34.29.94',
                        border: OutlineInputBorder(),
                      ),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    flex: 2,
                    child: TextField(
                      controller: _portController,
                      keyboardType: TextInputType.number,
                      decoration: const InputDecoration(
                        labelText: '端口',
                        border: OutlineInputBorder(),
                      ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              SizedBox(
                width: double.infinity,
                child: FilledButton.tonalIcon(
                  onPressed: _busy ? null : _connectManual,
                  icon: const Icon(Icons.link),
                  label: const Text('连接并验证'),
                ),
              ),
              const SizedBox(height: 8),
              Text(
                '电脑上查 IP：ipconfig 里的 IPv4 地址（或用项目里的 scripts 脚本打印）',
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
          ),
        ),
      );

  Widget _historyCard(List<ServerConfig> history) => Card(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Padding(
              padding: EdgeInsets.fromLTRB(16, 16, 16, 8),
              child: Text('历史地址（IP 变了就点一下试试）', style: TextStyle(fontWeight: FontWeight.bold)),
            ),
            for (final c in history)
              ListTile(
                dense: true,
                leading: const Icon(Icons.history),
                title: Text(c.origin),
                onTap: () async {
                  _hostController.text = c.host;
                  _portController.text = c.port.toString();
                  await _connectManual();
                },
              ),
          ],
        ),
      );
}

class _QrScanDialog extends StatefulWidget {
  const _QrScanDialog();

  @override
  State<_QrScanDialog> createState() => _QrScanDialogState();
}

class _QrScanDialogState extends State<_QrScanDialog> {
  final _controller = MobileScannerController(detectionSpeed: DetectionSpeed.noDuplicates);
  bool _handled = false;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Dialog(
      child: SizedBox(
        height: 320,
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.all(12),
              child: Row(
                children: [
                  const Expanded(child: Text('扫描电脑上显示的二维码')),
                  IconButton(
                    onPressed: () => Navigator.of(context).pop(),
                    icon: const Icon(Icons.close),
                  ),
                ],
              ),
            ),
            Expanded(
              child: MobileScanner(
                controller: _controller,
                errorBuilder: (context, error) => Center(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Text('相机不可用：' + error.errorCode.name + '\n可退回手动输入 IP'),
                  ),
                ),
                onDetect: (capture) {
                  if (_handled) return;
                  final code = capture.barcodes.isEmpty ? null : capture.barcodes.first.rawValue;
                  if (code == null || code.isEmpty) return;
                  _handled = true;
                  Navigator.of(context).pop(code);
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}
