import 'dart:async';
import 'dart:convert';
import 'dart:io';

import '../config/server_config.dart';

/// 私聊 WebSocket：ws://host:8080/ws/chat?token=xxx（Sa-Token 握手鉴权）。
///
/// 只负责收包；发包走 REST（后端同样会广播给对面），这样 ACK 与落库都有保证，
/// WebSocket 掉线也不会丢消息。
class ChatSocket {
  ChatSocket(this.config, this.token);

  final ServerConfig config;
  final String token;

  WebSocket? _socket;
  StreamSubscription<dynamic>? _sub;
  Timer? _retry;
  int _attempt = 0;
  bool _closed = false;

  final _messages = StreamController<Map<String, dynamic>>.broadcast();
  final _status = StreamController<bool>.broadcast();

  Stream<Map<String, dynamic>> get messages => _messages.stream;
  Stream<bool> get connected => _status.stream;

  Future<void> connect() async {
    if (_closed || token.isEmpty) return;
    try {
      final socket = await WebSocket.connect(
        config.chatWs(token).toString(),
      ).timeout(const Duration(seconds: 6));
      if (_closed) {
        await socket.close();
        return;
      }
      _socket = socket;
      _attempt = 0;
      _status.add(true);
      _sub = socket.listen(
        (dynamic raw) {
          if (raw is! String) return;
          try {
            final decoded = jsonDecode(raw);
            if (decoded is Map) {
              _messages.add(decoded.cast<String, dynamic>());
            }
          } catch (_) {
            // 非 JSON 包忽略
          }
        },
        onDone: _scheduleReconnect,
        onError: (Object _) => _scheduleReconnect(),
        cancelOnError: true,
      );
    } catch (_) {
      _status.add(false);
      _scheduleReconnect();
    }
  }

  /// 退避重连：1s, 2s, 4s, 8s，最多 15s（和 Web 端策略一致）。
  void _scheduleReconnect() {
    if (_closed) return;
    _status.add(false);
    _sub?.cancel();
    _sub = null;
    _socket = null;
    _attempt = (_attempt + 1).clamp(1, 4);
    final delay = Duration(seconds: 1 << (_attempt - 1));
    _retry?.cancel();
    _retry = Timer(delay, connect);
  }

  Future<void> dispose() async {
    _closed = true;
    _retry?.cancel();
    await _sub?.cancel();
    await _socket?.close();
    await _messages.close();
    await _status.close();
  }
}
