import 'dart:convert';

/// 服务器连接配置。
///
/// 校园网 IP 会变，所以这里只存「最近一次可用」的地址 + 端口，
/// 真正的连通性由 ServerConfigService.probe 现测现用，不信任本地缓存。
class ServerConfig {
  const ServerConfig({
    required this.host,
    this.port = defaultPort,
    this.savedAt,
    this.label,
  });

  static const int defaultPort = 8080;

  final String host;
  final int port;
  final DateTime? savedAt;

  /// 备注，例如「宿舍台式机」「实验室」
  final String? label;

  bool get isEmpty => host.trim().isEmpty;

  /// 源地址，例如 http://192.168.1.7:8080
  String get origin =>
      Uri(scheme: 'http', host: host.trim(), port: port).toString();

  /// 接口前缀：后端所有接口都在 /api/v1 下
  String get apiBase => origin + '/api/v1';

  /// WebSocket 聊天地址（Sa-Token 走 ?token= 握手鉴权）
  Uri chatWs(String token) => Uri(
        scheme: 'ws',
        host: host.trim(),
        port: port,
        path: '/ws/chat',
        queryParameters: {'token': token},
      );

  /// 把后端返回的绝对地址（MinIO / 静态资源）重写到当前服务器地址上。
  ///
  /// 后端 public-base-url 默认写死 http://127.0.0.1:9010/ershou，
  /// 手机访问 127.0.0.1 会指向手机自己，所以只保留 path 并换成本机地址。
  String resolveAssetUrl(String? raw) {
    if (raw == null || raw.trim().isEmpty) return '';
    final value = raw.trim();
    if (value.startsWith('/')) return origin + value;
    final uri = Uri.tryParse(value);
    if (uri == null || !uri.hasScheme) return origin + '/' + value;
    // 只换主机名、保留端口：图片在 MinIO(9010)，后端 8080 只提供 API。
    // 之前把端口一起改成 8080，导致每个图片请求都拿到 {"code":90001} 的 JSON。
    const localHosts = {'minio', 'localhost', '127.0.0.1', '0.0.0.0'};
    final isBackendHost = uri.host == host.trim();
    if (!isBackendHost && !localHosts.contains(uri.host)) return value;
    return Uri(
      scheme: uri.scheme,
      host: host.trim(),
      port: uri.hasPort ? uri.port : null,
      path: uri.path,
      query: uri.hasQuery ? uri.query : null,
    ).toString();
  }

  ServerConfig copyWith({
    String? host,
    int? port,
    String? label,
    DateTime? savedAt,
  }) =>
      ServerConfig(
        host: host ?? this.host,
        port: port ?? this.port,
        label: label ?? this.label,
        savedAt: savedAt ?? this.savedAt,
      );

  Map<String, dynamic> toJson() => {
        'host': host,
        'port': port,
        'label': label,
        'savedAt': savedAt?.toIso8601String(),
      };

  static ServerConfig fromJson(Map<String, dynamic> json) => ServerConfig(
        host: (json['host'] as String?) ?? '',
        port: (json['port'] as num?)?.toInt() ?? defaultPort,
        label: json['label'] as String?,
        savedAt: json['savedAt'] == null
            ? null
            : DateTime.tryParse(json['savedAt'] as String),
      );

  String encode() => jsonEncode(toJson());

  static ServerConfig? decode(String? raw) {
    if (raw == null || raw.isEmpty) return null;
    try {
      return ServerConfig.fromJson(jsonDecode(raw) as Map<String, dynamic>);
    } catch (_) {
      return null;
    }
  }

  @override
  String toString() => host + ':' + port.toString();
}
