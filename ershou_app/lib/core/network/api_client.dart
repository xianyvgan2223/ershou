import 'dart:io';

import 'package:dio/dio.dart';

import '../config/server_config.dart';

/// 后端统一响应体 { code, message, data, traceId }，code == 0 为成功。
class ApiEnvelope<T> {
  const ApiEnvelope({required this.code, required this.message, this.data, this.traceId});

  final int code;
  final String message;
  final T? data;
  final String? traceId;
}

class ApiException implements Exception {
  ApiException(this.code, this.message, {this.traceId});

  final int code;
  final String message;
  final String? traceId;

  bool get isUnauthorized => code == 10002 || code == 401;

  @override
  String toString() => message.isEmpty ? 'ApiException(' + code.toString() + ')' : message;
}

/// 极简 API 客户端：
///  - 自动带上 Sa-Token 的 Authorization: Bearer xxx
///  - 自动拆统一响应体，把非 0 code 变成 ApiException
///  - baseUrl 为空时抛出可读错误，交给「服务器连接」页处理
class ApiClient {
  ApiClient();

  Dio? _dio;
  ServerConfig? _config;
  String? _token;
  void Function()? onUnauthorized;

  ServerConfig? get config => _config;
  String get origin => _config?.origin ?? '';

  void configure(ServerConfig? config) {
    if (_config?.origin == config?.origin && _dio != null) {
      _config = config;
      return;
    }
    _config = config;
    _dio?.close(force: true);
    _dio = null;
  }

  void setToken(String? token) => _token = token;

  String? get token => _token;

  Dio get dio {
    final existing = _dio;
    if (existing != null) return existing;
    final config = _config;
    if (config == null || config.isEmpty) {
      throw ApiException(-1, '还没有配置服务器地址');
    }
    final client = Dio(BaseOptions(
      baseUrl: config.apiBase,
      connectTimeout: const Duration(seconds: 6),
      receiveTimeout: const Duration(seconds: 12),
      sendTimeout: const Duration(seconds: 20),
      validateStatus: (_) => true,
      headers: const {'Accept': 'application/json'},
      responseType: ResponseType.json,
    ));
    client.interceptors.add(InterceptorsWrapper(
      onRequest: (options, handler) {
        final t = _token;
        if (t != null && t.isNotEmpty) {
          options.headers['Authorization'] = 'Bearer ' + t;
        }
        handler.next(options);
      },
    ));
    _dio = client;
    return client;
  }

  Future<T> get<T>(String path, {Map<String, dynamic>? query}) =>
      _send<T>(() => dio.get<dynamic>(path, queryParameters: _clean(query)));

  Future<T> post<T>(String path, {Object? data, Map<String, dynamic>? query}) =>
      _send<T>(() => dio.post<dynamic>(path, data: data, queryParameters: _clean(query)));

  Future<T> patch<T>(String path, {Object? data}) =>
      _send<T>(() => dio.patch<dynamic>(path, data: data));

  Future<T> delete<T>(String path, {Object? data}) =>
      _send<T>(() => dio.delete<dynamic>(path, data: data));

  Future<ApiEnvelope<T>> envelope<T>(
    Future<Response<dynamic>> Function() request, {
    T Function(dynamic data)? parse,
  }) async {
    Response<dynamic> resp;
    try {
      resp = await request();
    } on DioException catch (e) {
      throw ApiException(-2, _humanizeDio(e));
    }
    final raw = resp.data;
    Map<String, dynamic>? map;
    if (raw is Map) {
      map = raw.cast<String, dynamic>();
    } else if (raw is String && raw.trim().isNotEmpty) {
      throw ApiException(resp.statusCode ?? -3, '响应不是 JSON：' + raw.substring(0, raw.length > 120 ? 120 : raw.length));
    }
    if (map == null) {
      if (resp.statusCode == 200) {
        throw ApiException(-3, '响应体为空');
      }
      throw ApiException(resp.statusCode ?? -3, 'HTTP ' + (resp.statusCode?.toString() ?? '??'));
    }
    final code = (map['code'] as num?)?.toInt() ?? -1;
    final message = (map['message'] as String?) ?? '';
    final traceId = map['traceId'] as String?;
    if (code != 0) {
      if (code == 10002) onUnauthorized?.call();
      throw ApiException(code, message.isEmpty ? '请求失败' : message, traceId: traceId);
    }
    final data = map['data'];
    return ApiEnvelope<T>(
      code: code,
      message: message,
      data: parse == null ? data as T? : (data == null ? null : parse(data)),
      traceId: traceId,
    );
  }

  Future<T> _send<T>(Future<Response<dynamic>> Function() request) async {
    final env = await envelope<T>(request);
    final data = env.data;
    if (data == null) {
      // void 接口：返回一个空的 Map 以便调用方忽略
      return (const <String, dynamic>{}) as T;
    }
    return data;
  }

  static Map<String, dynamic>? _clean(Map<String, dynamic>? query) {
    if (query == null) return null;
    final out = <String, dynamic>{};
    query.forEach((k, v) {
      if (v == null) return;
      if (v is String && v.isEmpty) return;
      out[k] = v;
    });
    return out;
  }

  static String _humanizeDio(DioException e) {
    switch (e.type) {
      case DioExceptionType.connectionTimeout:
      case DioExceptionType.sendTimeout:
      case DioExceptionType.receiveTimeout:
      case DioExceptionType.transformTimeout:
        return '请求超时，检查服务器地址与实际网段';
      case DioExceptionType.connectionError:
        return '连不上服务器，检查后端是否启动、手机与电脑是否同一 WiFi';
      case DioExceptionType.badCertificate:
        return '证书错误';
      case DioExceptionType.cancel:
        return '请求已取消';
      case DioExceptionType.badResponse:
        return 'HTTP ' + (e.response?.statusCode?.toString() ?? '??');
      case DioExceptionType.unknown:
        if (e.error is SocketException) return '网络不可达：' + (e.error as SocketException).message;
        return e.message ?? '未知网络错误';
    }
  }
}
