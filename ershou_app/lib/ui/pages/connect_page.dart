import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

import '../../core/config/server_config.dart';
import '../../core/discovery/lan_scanner.dart';
import '../../core/providers.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';

/// 连接服务器：一键扫描 / 手动输入 / 扫码 / 历史地址。
class ConnectPage extends ConsumerStatefulWidget {
  const ConnectPage({super.key});

  @override
  ConsumerState<ConnectPage> createState() => _ConnectPageState();
}

class _ConnectPageState extends ConsumerState<ConnectPage> {
  final _hostController = TextEditingController();
  final _portController =
      TextEditingController(text: ServerConfig.defaultPort.toString());
  final _found = <ServerCandidate>[];

  StreamSubscription<Object>? _sub;
  DiscoveryProgress? _progress;
  String? _error;
  String? _hint;
  bool _scanning = false;
  bool _busy = false;
  bool _autoTried = false;

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

  /// 冷启动先试上次地址；失败或没有记录就自动扫一次，用户不用手动点。
  Future<void> _trySaved() async {
    if (_autoTried) return;
    _autoTried = true;
    final saved = ref.read(serverConfigServiceProvider).load();
    if (saved == null || saved.isEmpty) {
      await Future<void>.delayed(const Duration(milliseconds: 400));
      if (mounted) _startScan();
      return;
    }
    setState(() {
      _busy = true;
      _hint = '正在探测上次的地址 ' + saved.toString() + ' …';
    });
    final hit = await ref.read(lanScannerProvider).trySaved(saved);
    if (!mounted) return;
    setState(() {
      _busy = false;
      _hint = hit == null
          ? '上次的 ' + saved.toString() + ' 已经连不上（校园网换网后 IP 会变）'
          : null;
    });
    if (hit != null) {
      await _use(hit, silent: true);
    } else if (mounted) {
      // 校园网 IP 变了是常态：自动接一次扫描，省掉用户点一下
      await Future<void>.delayed(const Duration(milliseconds: 400));
      if (mounted && !_scanning) _startScan();
    }
  }

  void _startScan() {
    _sub?.cancel();
    setState(() {
      _scanning = true;
      _found.clear();
      _error = null;
      _hint = null;
      _progress = const DiscoveryProgress(phase: '准备中…');
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
          _error = '扫描出错：' + e.toString();
        });
      },
      onDone: () {
        if (!mounted) return;
        setState(() {
          _scanning = false;
          _progress = null;
          if (_found.isEmpty) {
            _error = '没找到服务器。检查：① 后端已启动；② 手机和电脑连同一个 WiFi；'
                '③ 电脑 IP 变了（跑一下 scripts/lan-info.ps1）。';
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
      _hint = '已停止扫描';
    });
  }

  Future<void> _connectManual() async {
    final host = _hostController.text.trim();
    final port =
        int.tryParse(_portController.text.trim()) ?? ServerConfig.defaultPort;
    if (host.isEmpty) {
      setState(() => _error = '先填服务器 IP');
      return;
    }
    FocusScope.of(context).unfocus();
    setState(() {
      _busy = true;
      _error = null;
      _hint = '正在连接 ' + host + ':' + port.toString() + ' …';
    });
    final hit = await ref.read(lanScannerProvider).tryManual(host, port);
    if (!mounted) return;
    setState(() {
      _busy = false;
      _hint = null;
      _error = hit == null
          ? '连不上 ' + host + ':' + port.toString() + '，确认后端已启动且在同一局域网'
          : null;
    });
    if (hit != null) await _use(hit);
  }

  Future<void> _use(ServerCandidate candidate, {bool silent = false}) async {
    await ref.read(activeServerProvider.notifier).use(candidate.config);
    if (!mounted) return;
    _sub?.cancel();
    setState(() {
      _scanning = false;
      _progress = null;
      _error = null;
      _hint = null;
    });
    if (silent) return;
    showToast(context, '已连接 ' + candidate.config.origin);
    if (Navigator.of(context).canPop()) Navigator.of(context).pop();
  }

  Future<void> _scanQr() async {
    final value = await showDialog<String>(
      context: context,
      builder: (_) => const _QrScanDialog(),
    );
    if (value == null || value.isEmpty) return;
    final parsed = _parseQr(value);
    if (parsed == null) {
      setState(() => _error = '二维码内容看不懂：' + value);
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
      final host =
          RegExp('"host"\\s*:\\s*"([^"]+)"').firstMatch(text)?.group(1);
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
    final port =
        parts.length > 1 ? int.tryParse(parts[1].split('/').first) : null;
    return ServerConfig(host: host, port: port ?? ServerConfig.defaultPort);
  }

  @override
  Widget build(BuildContext context) {
    final history = ref.watch(serverConfigServiceProvider).loadHistory();
    final current = ref.watch(activeServerProvider);
    return Scaffold(
      backgroundColor: AppColors.bg,
      appBar: AppBar(
        title: const Text('连接服务器'),
        actions: [
          IconButton(
            onPressed: _scanQr,
            icon: const Icon(Icons.qr_code_scanner_rounded),
            tooltip: '扫二维码',
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(12, 12, 12, 32),
        children: [
          _heroCard(current),
          const SizedBox(height: 12),
          _scanCard(),
          if (_found.isNotEmpty) ...[
            const SizedBox(height: 12),
            _foundCard(),
          ],
          const SizedBox(height: 12),
          _manualCard(),
          if (history.isNotEmpty) ...[
            const SizedBox(height: 12),
            _historyCard(history),
          ],
          const SizedBox(height: 12),
          _tipsCard(),
        ],
      ),
    );
  }

  Widget _heroCard(ServerConfig? current) {
    final connected = current != null && !current.isEmpty;
    return Container(
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        gradient: const LinearGradient(
          colors: [AppColors.brand, Color(0xFF00D08A)],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        borderRadius: BorderRadius.circular(AppRadius.card),
        boxShadow: const [
          BoxShadow(color: Color(0x3300B578), blurRadius: 18, offset: Offset(0, 8)),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(Icons.wifi_tethering_rounded, color: Colors.white, size: 22),
              const SizedBox(width: 8),
              const Text(
                '校园二手 · 手机端',
                style: TextStyle(
                  color: Colors.white,
                  fontSize: 17,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          Text(
            connected
                ? '当前：' + current.origin
                : '还没有连接服务器，点下面的「一键扫描」让 App 自己找',
            style: const TextStyle(color: Colors.white, fontSize: 13, height: 1.5),
          ),
          if (_hint != null) ...[
            const SizedBox(height: 8),
            Row(
              children: [
                const SizedBox(
                  width: 12,
                  height: 12,
                  child: CircularProgressIndicator(
                    strokeWidth: 1.6,
                    color: Colors.white,
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    _hint!,
                    style: const TextStyle(color: Colors.white, fontSize: 12),
                  ),
                ),
              ],
            ),
          ],
        ],
      ),
    );
  }

  Widget _scanCard() {
    final progress = _progress;
    return AppCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Expanded(
                child: Text(
                  '一键扫描',
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
                ),
              ),
              if (_scanning)
                TextButton.icon(
                  onPressed: _stopScan,
                  icon: const Icon(Icons.stop_circle_outlined, size: 18),
                  label: const Text('停止'),
                )
              else
                FilledButton.icon(
                  onPressed: _busy ? null : _startScan,
                  icon: const Icon(Icons.wifi_find_rounded, size: 18),
                  label: const Text('开始扫描'),
                ),
            ],
          ),
          const SizedBox(height: 6),
          const Text(
            '依次尝试：UDP 广播 → USB 隧道 → 手机所在网段',
            style: TextStyle(fontSize: 12, color: AppColors.inkFaint),
          ),
          if (progress != null) ...[
            const SizedBox(height: 14),
            ClipRRect(
              borderRadius: BorderRadius.circular(999),
              child: LinearProgressIndicator(
                value: progress.total > 0 ? progress.ratio : null,
                minHeight: 6,
                backgroundColor: AppColors.line,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              progress.phase +
                  (progress.total > 0
                      ? '（' +
                          progress.scanned.toString() +
                          '/' +
                          progress.total.toString() +
                          '）'
                      : ''),
              style: const TextStyle(fontSize: 12, color: AppColors.inkSoft),
            ),
          ],
          if (_error != null) ...[
            const SizedBox(height: 12),
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: const Color(0xFFFFF4F1),
                borderRadius: BorderRadius.circular(10),
              ),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Icon(Icons.error_outline, size: 16, color: AppColors.price),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      _error!,
                      style: const TextStyle(
                        fontSize: 12.5,
                        color: AppColors.price,
                        height: 1.5,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _foundCard() {
    return AppCard(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 6),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const SectionHeader(title: '发现的服务器'),
          for (final c in _found)
            InkWell(
              onTap: () => _use(c),
              borderRadius: BorderRadius.circular(10),
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 10),
                child: Row(
                  children: [
                    Container(
                      padding: const EdgeInsets.all(8),
                      decoration: BoxDecoration(
                        color: AppColors.brandSoft,
                        borderRadius: BorderRadius.circular(10),
                      ),
                      child: const Icon(
                        Icons.dns_outlined,
                        size: 18,
                        color: AppColors.brandDark,
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            c.config.origin,
                            style: const TextStyle(
                              fontSize: 14.5,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                          const SizedBox(height: 2),
                          Text(
                            c.methodLabel + ' · ' + c.note,
                            style: const TextStyle(fontSize: 11.5, color: AppColors.inkFaint),
                          ),
                        ],
                      ),
                    ),
                    const Icon(Icons.chevron_right, color: AppColors.inkFaint),
                  ],
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _manualCard() {
    return AppCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const SectionHeader(title: '手动输入'),
          Row(
            children: [
              Expanded(
                flex: 3,
                child: TextField(
                  controller: _hostController,
                  keyboardType: TextInputType.text,
                  decoration: const InputDecoration(
                    labelText: '服务器 IP',
                    hintText: '例如 192.168.17.112',
                  ),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                flex: 2,
                child: TextField(
                  controller: _portController,
                  keyboardType: TextInputType.number,
                  decoration: const InputDecoration(labelText: '端口'),
                ),
              ),
            ],
          ),
          const SizedBox(height: 14),
          SizedBox(
            width: double.infinity,
            child: FilledButton.icon(
              onPressed: _busy ? null : _connectManual,
              icon: const Icon(Icons.link_rounded, size: 18),
              label: const Text('连接并验证'),
            ),
          ),
        ],
      ),
    );
  }

  Widget _historyCard(List<ServerConfig> history) {
    return AppCard(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 6),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const SectionHeader(title: '历史地址', subtitle: 'IP 变了就挨个点一下'),
          for (final c in history)
            ListTile(
              contentPadding: EdgeInsets.zero,
              dense: true,
              visualDensity: VisualDensity.compact,
              leading: const Icon(Icons.history, size: 18, color: AppColors.inkFaint),
              title: Text(c.origin, style: const TextStyle(fontSize: 14)),
              trailing: const Icon(Icons.chevron_right, size: 18, color: AppColors.inkFaint),
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

  Widget _tipsCard() {
    return AppCard(
      color: const Color(0xFFFBFBFC),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: const [
          SectionHeader(title: '连不上怎么办'),
          Text(
            '1. 电脑上跑 scripts\\lan-info.ps1，它会打印手机该连的 IP；\n'
            '2. 手机和电脑必须在同一个 WiFi，校园网开了 AP 隔离时会互相看不见，'
            '可以改用手机热点让电脑连；\n'
            '3. 兜底：电脑上执行 adb reverse tcp:8080 tcp:8080，再点一键扫描（会走 USB 隧道）。',
            style: TextStyle(fontSize: 12.5, height: 1.8, color: AppColors.inkSoft),
          ),
        ],
      ),
    );
  }
}

class _QrScanDialog extends StatefulWidget {
  const _QrScanDialog();

  @override
  State<_QrScanDialog> createState() => _QrScanDialogState();
}

class _QrScanDialogState extends State<_QrScanDialog> {
  final _controller =
      MobileScannerController(detectionSpeed: DetectionSpeed.noDuplicates);
  bool _handled = false;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Dialog(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
      clipBehavior: Clip.antiAlias,
      child: SizedBox(
        height: 340,
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 8, 12),
              child: Row(
                children: [
                  const Expanded(
                    child: Text(
                      '扫描电脑上的二维码',
                      style: TextStyle(fontWeight: FontWeight.w600),
                    ),
                  ),
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
                    child: Text(
                      '相机不可用（' + error.errorCode.name + '）\n可以退回手动输入 IP',
                      textAlign: TextAlign.center,
                      style: const TextStyle(color: AppColors.inkSoft),
                    ),
                  ),
                ),
                onDetect: (capture) {
                  if (_handled) return;
                  final code =
                      capture.barcodes.isEmpty ? null : capture.barcodes.first.rawValue;
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
