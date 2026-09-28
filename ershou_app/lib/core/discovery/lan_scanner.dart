import 'dart:async';
import 'dart:convert';
import 'dart:io';

import '../config/server_config.dart';
import '../config/server_config_service.dart';

/// 局域网服务器的发现方式。
enum DiscoveryMethod { saved, broadcast, usbTunnel, sweep, manual }

class ServerCandidate {
  const ServerCandidate({
    required this.config,
    required this.method,
    this.latencyMs = 0,
    this.note = '',
  });

  final ServerConfig config;
  final DiscoveryMethod method;
  final int latencyMs;
  final String note;

  String get methodLabel {
    switch (method) {
      case DiscoveryMethod.saved:
        return '上次连接';
      case DiscoveryMethod.broadcast:
        return '广播发现';
      case DiscoveryMethod.usbTunnel:
        return 'USB 隧道';
      case DiscoveryMethod.sweep:
        return '网段扫描';
      case DiscoveryMethod.manual:
        return '手动输入';
    }
  }
}

class DiscoveryProgress {
  const DiscoveryProgress({
    this.scanned = 0,
    this.total = 0,
    this.current = '',
    this.phase = '',
  });

  final int scanned;
  final int total;
  final String current;
  final String phase;

  double get ratio => total <= 0 ? 0 : (scanned / total).clamp(0, 1).toDouble();
}

/// 一键连接：在本机所有网段里找「真的是本项目后端」的那台机器。
///
/// 后端所有接口都在 8080，所以候选地址固定带 8080 端口。
class LanScanner {
  LanScanner(this._probe);

  static const int defaultPort = ServerConfig.defaultPort;
  static const int broadcastPort = 48899;

  final ServerConfigService _probe;

  /// 第一优先级：上次用的地址还能不能通。
  Future<ServerCandidate?> trySaved(ServerConfig? saved) async {
    if (saved == null || saved.isEmpty) return null;
    final r = await _probe.probe(saved, timeout: const Duration(milliseconds: 1500));
    if (!r.ok) return null;
    return ServerCandidate(
      config: saved,
      method: DiscoveryMethod.saved,
      latencyMs: r.latencyMs,
      note: r.message,
    );
  }

  /// 手动输入地址的校验。
  Future<ServerCandidate?> tryManual(String host, int port) async {
    final config = ServerConfig(host: host.trim(), port: port);
    final r = await _probe.probe(config);
    if (!r.ok) return null;
    return ServerCandidate(
      config: config,
      method: DiscoveryMethod.manual,
      latencyMs: r.latencyMs,
      note: r.message,
    );
  }

  /// 完整发现流程：UDP 广播 → 本机回环（adb reverse 隧道）→ 网段扫描。
  Stream<Object> discover() async* {
    final interfaces = await _wifiSubnets();
    yield DiscoveryProgress(phase: '正在广播探测（最快）…');
    final broadcastHit = await _broadcast();
    if (broadcastHit != null) {
      yield broadcastHit;
      return;
    }

    // adb reverse tcp:8080 tcp:8080 时，后端会被映射到手机自己的 127.0.0.1:8080；
    // 校园网 AP 隔离下这是最稳的一条路，所以放在网段扫描之前。
    yield DiscoveryProgress(phase: '探测 USB 隧道（adb reverse）…', total: 1);
    final loopback = await _probe.probe(
      const ServerConfig(host: '127.0.0.1'),
      timeout: const Duration(milliseconds: 1200),
    );
    if (loopback.ok) {
      yield ServerCandidate(
        config: loopback.config,
        method: DiscoveryMethod.usbTunnel,
        latencyMs: loopback.latencyMs,
        note: 'USB 隧道（adb reverse）',
      );
      return;
    }

    final candidates = await _candidates(interfaces);
    if (candidates.isEmpty) {
      yield DiscoveryProgress(phase: '没有找到可扫描的局域网网段');
      return;
    }
    yield DiscoveryProgress(
      phase: '正在扫描网段（' + _subnetLabel(interfaces) + '）…',
      total: candidates.length,
    );

    const batchSize = 64;
    var scanned = 0;
    for (var i = 0; i < candidates.length; i += batchSize) {
      final end = (i + batchSize).clamp(0, candidates.length);
      final batch = candidates.sublist(i, end);
      final results = await Future.wait(batch.map((host) async {
        final r = await _probe.probe(
          ServerConfig(host: host, port: defaultPort),
          timeout: const Duration(milliseconds: 700),
        );
        return r.ok ? r : null;
      }));
      scanned += batch.length;
      yield DiscoveryProgress(
        scanned: scanned,
        total: candidates.length,
        current: batch.isEmpty ? '' : batch.last,
      );
      for (final hit in results) {
        if (hit != null) {
          yield ServerCandidate(
            config: hit.config,
            method: DiscoveryMethod.sweep,
            latencyMs: hit.latencyMs,
            note: hit.message,
          );
        }
      }
    }
  }

  /// UDP 广播：后端如果实现了 /lan-discovery 应答，这一步就能秒连。
  Future<ServerCandidate?> _broadcast() async {
    RawDatagramSocket? socket;
    try {
      socket = await RawDatagramSocket.bind(InternetAddress.anyIPv4, 0);
      socket.broadcastEnabled = true;
      final payload = utf8.encode(jsonEncode({'app': 'ershou-app', 'probe': 'lan'}));
      // 直发 + 广播都试一遍：部分校园 WiFi 会丢广播包
      for (final target in await _broadcastTargets()) {
        try {
          socket.send(payload, target, broadcastPort);
        } catch (_) {
          // 单个目标失败不影响其它目标
        }
      }
      final completer = Completer<ServerCandidate?>();
      final sub = socket.listen((event) {
        if (event != RawSocketEvent.read) return;
        final dg = socket?.receive();
        if (dg == null || completer.isCompleted) return;
        try {
          final text = utf8.decode(dg.data);
          final map = jsonDecode(text);
          if (map is! Map || map['app'] != 'ershou-server') return;
          final port = (map['port'] as num?)?.toInt() ?? defaultPort;
          completer.complete(ServerCandidate(
            config: ServerConfig(
              host: dg.address.address,
              port: port,
              label: (map['name'] as String?) ?? '广播发现的服务器',
            ),
            method: DiscoveryMethod.broadcast,
            note: '广播应答',
          ));
        } catch (_) {
          // 不是我们的应答包，忽略
        }
      });
      try {
        return await completer.future.timeout(const Duration(milliseconds: 1200));
      } on TimeoutException {
        return null;
      } finally {
        await sub.cancel();
      }
    } catch (_) {
      return null;
    } finally {
      socket?.close();
    }
  }

  Future<List<InternetAddress>> _broadcastTargets() async {
    final targets = <InternetAddress>[InternetAddress('255.255.255.255')];
    for (final list in await NetworkInterface.list(type: InternetAddressType.IPv4)) {
      for (final addr in list.addresses) {
        if (addr.isLoopback || _isVirtual(addr.address)) continue;
        final parts = addr.address.split('.');
        if (parts.length != 4) continue;
        targets.add(InternetAddress(parts[0] + '.' + parts[1] + '.' + parts[2] + '.255'));
      }
    }
    return targets;
  }

  /// 本机所有可用于扫描的 /24 网段前缀，例如 ["10.34.29", "192.168.1"]。
  Future<List<String>> _wifiSubnets() async {
    final prefixes = <String>[];
    try {
      for (final list in await NetworkInterface.list(type: InternetAddressType.IPv4)) {
        for (final addr in list.addresses) {
          if (addr.isLoopback || _isVirtual(addr.address)) continue;
          prefixes.add(_prefixOf(addr.address));
        }
      }
    } catch (_) {
      // 拿不到网卡信息就退化为常见网段
    }
    for (final fallback in const ['192.168.1', '192.168.0', '192.168.31', '10.0.0']) {
      if (!prefixes.contains(fallback)) prefixes.add(fallback);
    }
    return prefixes;
  }

  static String _prefixOf(String ip) {
    final parts = ip.split('.');
    if (parts.length != 4) return ip;
    return parts[0] + '.' + parts[1] + '.' + parts[2];
  }

  static String _subnetLabel(List<String> prefixes) =>
      prefixes.isEmpty ? '本机网段' : prefixes.take(2).map((p) => p + '.x').join(' / ');

  static bool _isVirtual(String ip) {
    if (ip.startsWith('169.254.')) return true;
    if (ip.startsWith('172.17.') || ip.startsWith('172.18.')) return true;
    return false;
  }

  /// 候选地址排序：常用网段优先，常见主机位次之，兼顾扫描速度。
  Future<List<String>> _candidates(List<String> prefixes) async {
    final ordered = <String>[];
    // 局域网里最常见的网段排前面
    const preferred = ['192.168.1', '192.168.0', '192.168.31', '192.168.2', '10.0.0'];
    final sorted = [...prefixes]..sort((a, b) {
        final ia = preferred.indexOf(a);
        final ib = preferred.indexOf(b);
        if (ia == -1 && ib == -1) return 0;
        if (ia == -1) return 1;
        if (ib == -1) return -1;
        return ia.compareTo(ib);
      });
    const commonHosts = [1, 2, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 114, 115, 116, 117, 118, 119, 120];
    for (final prefix in sorted) {
      for (final host in commonHosts) {
        ordered.add(prefix + '.' + host.toString());
      }
    }
    for (final prefix in sorted) {
      for (var host = 1; host <= 254; host++) {
        final ip = prefix + '.' + host.toString();
        if (ordered.contains(ip)) continue;
        ordered.add(ip);
      }
    }
    ordered.add('127.0.0.1');
    return ordered;
  }
}
