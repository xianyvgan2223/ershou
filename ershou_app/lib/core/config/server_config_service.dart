import 'dart:async';
import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'server_config.dart';

/// 后端连通性探测结果。
class ServerProbeResult {
  const ServerProbeResult({
    required this.ok,
    required this.config,
    this.message = '',
    this.latencyMs = 0,
  });

  final bool ok;
  final ServerConfig config;
  final String message;
  final int latencyMs;
}

/// 服务器地址的持久化 + 连通性探测。
class ServerConfigService {
  ServerConfigService(this._prefs);

  static const _key = 'ershou.server.config';
  static const _historyKey = 'ershou.server.history';

  final SharedPreferences _prefs;

  ServerConfig? load() => ServerConfig.decode(_prefs.getString(_key));

  List<ServerConfig> loadHistory() => (_prefs.getStringList(_historyKey) ?? [])
      .map(ServerConfig.decode)
      .whereType<ServerConfig>()
      .toList();

  Future<void> save(ServerConfig config) async {
    await _prefs.setString(_key, config.copyWith(savedAt: DateTime.now()).encode());
    final history =
        loadHistory().where((c) => c.host != config.host || c.port != config.port).toList();
    history.insert(0, config);
    await _prefs.setStringList(
      _historyKey,
      history.take(6).map((c) => c.encode()).toList(),
    );
  }

  Future<void> clear() async {
    await _prefs.remove(_key);
  }

  /// 单点探测：不看端口开不开，直接打后端公开接口确认真的是本项目后端。
  ///
  /// /api/v1/categories 公开、无副作用、命中 Redis 字典缓存，非常轻。
  Future<ServerProbeResult> probe(
    ServerConfig config, {
    Duration timeout = const Duration(milliseconds: 1200),
  }) async {
    if (config.isEmpty) {
      return ServerProbeResult(ok: false, config: config, message: '地址为空');
    }
    final dio = Dio(BaseOptions(
      connectTimeout: timeout,
      receiveTimeout: timeout,
      sendTimeout: timeout,
      validateStatus: (_) => true,
      headers: const {'Accept': 'application/json'},
    ));
    final started = DateTime.now();
    try {
      final resp = await dio.get<dynamic>(config.apiBase + '/categories');
      final latency = DateTime.now().difference(started).inMilliseconds;
      if (resp.statusCode != 200) {
        return ServerProbeResult(
          ok: false,
          config: config,
          latencyMs: latency,
          message: 'HTTP ' + resp.statusCode.toString(),
        );
      }
      final body = resp.data;
      Map<String, dynamic>? map;
      if (body is Map) {
        map = body.cast<String, dynamic>();
      } else if (body is String) {
        final decoded = jsonDecode(body);
        if (decoded is Map) map = decoded.cast<String, dynamic>();
      }
      if (map == null || map['code'] != 0) {
        return ServerProbeResult(
          ok: false,
          config: config,
          latencyMs: latency,
          message: '该地址不是本项目后端',
        );
      }
      return ServerProbeResult(
        ok: true,
        config: config,
        latencyMs: latency,
        message: '已连接 · ' + latency.toString() + 'ms',
      );
    } catch (e) {
      return ServerProbeResult(ok: false, config: config, message: humanize(e));
    } finally {
      dio.close(force: true);
    }
  }

  static String humanize(Object e) {
    if (e is DioException) {
      switch (e.type) {
        case DioExceptionType.connectionTimeout:
        case DioExceptionType.sendTimeout:
        case DioExceptionType.receiveTimeout:
          return '连接超时（可能不在同一网段）';
        case DioExceptionType.connectionError:
          return '连不上（后端没启动 / 不同局域网）';
        default:
          return '网络错误：' + (e.message ?? e.type.name);
      }
    }
    return e.toString();
  }
}
