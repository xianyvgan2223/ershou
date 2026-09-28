// 真机联调前先在本机验证网络层：这些测试需要后端已启动（127.0.0.1:8080）。
// 跑法：flutter test test/lan_probe_test.dart
import 'dart:io';

// ignore: depend_on_referenced_packages

import 'package:ershou_app/core/config/server_config.dart';
import 'package:ershou_app/core/config/server_config_service.dart';
import 'package:ershou_app/core/discovery/lan_scanner.dart';
import 'package:ershou_app/data/api_repository.dart';
import 'package:ershou_app/core/network/api_client.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  // flutter_test 默认把所有 HTTP 请求短路成 400，这里关掉才能打真后端。
  // 注意：必须在 ensureInitialized() 之后清，binding 初始化时会装上 _MockHttpOverrides。
  HttpOverrides.global = null;

  // 被沙箱/防火墙限制的环境里连不上 127.0.0.1，这时整体跳过而不是报一堆假失败。
  var backendReachable = true;

  late ServerConfigService service;

  setUpAll(() async {
    try {
      final socket = await Socket.connect('127.0.0.1', 8080,
          timeout: const Duration(seconds: 2));
      socket.destroy();
    } catch (_) {
      backendReachable = false;
    }
  });

  setUp(() async {
    if (!backendReachable) return;
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    service = ServerConfigService(prefs);
  });

  // 统一入口：环境不通就 skip，避免把「沙箱拦了回环」误报成代码问题。
  void integrationTest(String description, Future<void> Function() body) {
    test(description, () async {
      if (!backendReachable) {
        markTestSkipped('本测试环境连不上 127.0.0.1:8080，跳过');
        return;
      }
      await body();
    });
  }

  integrationTest('探测 127.0.0.1:8080 会识别出本项目后端', () async {
    const config = ServerConfig(host: '127.0.0.1');
    final result = await service.probe(config);
    expect(result.ok, isTrue, reason: '后端没起？先跑 scripts/dev-server.ps1');
    expect(result.latencyMs, greaterThanOrEqualTo(0));
  });

  integrationTest('探测本机局域网 IP 也能通（手机走的就是这条路）', () async {
    final lanIp = await _firstLanIp();
    if (lanIp == null) {
      markTestSkipped('没有可用的局域网网卡');
      return;
    }
    final result = await service.probe(ServerConfig(host: lanIp));
    expect(result.ok, isTrue, reason: '后端没有监听 0.0.0.0，或防火墙拦了 ' + lanIp);
  });

  integrationTest('手动输入的错误地址会被判为不可用', () async {
    final result = await service.probe(
      const ServerConfig(host: '127.0.0.1', port: 6553),
      timeout: const Duration(milliseconds: 600),
    );
    expect(result.ok, isFalse);
  });

  integrationTest('LanScanner 能通过手动校验找到本机后端', () async {
    final scanner = LanScanner(service);
    final hit = await scanner.tryManual('127.0.0.1', 8080);
    expect(hit, isNotNull);
    expect(hit!.config.origin, 'http://127.0.0.1:8080');
  });

  integrationTest('ApiClient 能拆统一响应体并解析分类', () async {
    final client = ApiClient()..configure(const ServerConfig(host: '127.0.0.1'));
    final repo = ItemRepository(client);
    final categories = await repo.categories();
    expect(categories, isNotEmpty);
    expect(categories.first.name, isNotEmpty);
  });

  integrationTest('商品列表与详情能正常解析（含图片地址重写）', () async {
    final client = ApiClient()..configure(const ServerConfig(host: '127.0.0.1'));
    final repo = ItemRepository(client);
    final page = await repo.page(pageSize: 5);
    expect(page.records.length, lessThanOrEqualTo(5));
    if (page.records.isEmpty) {
      markTestSkipped('库里还没有商品');
      return;
    }
    final first = page.records.first;
    final detail = await repo.detail(first.id);
    expect(detail?.id, first.id);
    if (first.coverUrl.isNotEmpty) {
      final url = repo.imageUrl(first.coverUrl);
      expect(url.contains('127.0.0.1:9010'), isFalse,
          reason: 'MinIO 地址必须被重写到后端地址，否则手机加载不出图');
    }
  });
}

Future<String?> _firstLanIp() async {
  // 优先真实网卡（WLAN / Wi-Fi / Ethernet），排除 WSL、VMware、Radmin 这类虚拟网卡
  const virtualHints = ['vethernet', 'vmware', 'radmin', 'virtual', 'loopback', 'bluetooth'];
  try {
    final candidates = <String>[];
    for (final list in await NetworkInterface.list(type: InternetAddressType.IPv4)) {
      final name = list.name.toLowerCase();
      if (virtualHints.any(name.contains)) continue;
      for (final addr in list.addresses) {
        if (addr.isLoopback || addr.address.startsWith('169.254.')) continue;
        if (addr.address.startsWith('172.17.') || addr.address.startsWith('172.18.')) continue;
        if (addr.address.startsWith('26.')) continue; // Radmin VPN 网段
        candidates.add(addr.address);
      }
    }
    if (candidates.isEmpty) return null;
    return candidates.first;
  } catch (_) {
    return null;
  }
}