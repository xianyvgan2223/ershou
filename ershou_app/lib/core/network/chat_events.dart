import 'dart:convert';

import '../../data/models2.dart';

/// 服务端帧类型。所有推送都是 `{type, data}` 信封（ChatPushService.buildPayload），
/// 早期版本把整帧当成消息体解析，导致 message/ack/recall 全部被静默丢弃。
class ChatFrameType {
  const ChatFrameType._();

  // 服务端 → 客户端
  static const ready = 'ready';
  static const pong = 'pong';
  static const message = 'message';
  static const ack = 'ack';
  static const recall = 'recall';
  static const read = 'read';
  static const readAck = 'read-ack';
  static const error = 'error';

  // 客户端 → 服务端
  static const ping = 'ping';
  static const send = 'send';
}

/// 一帧已解析的 WebSocket 消息。
class ChatFrame {
  const ChatFrame(this.type, this.data);

  final String type;
  final Map<String, dynamic> data;

  /// 非 JSON、不是信封、缺 type 一律返回 null（调用方忽略即可，不要抛）。
  static ChatFrame? decode(Object? raw) {
    if (raw is! String || raw.isEmpty) return null;
    Object? decoded;
    try {
      decoded = jsonDecode(raw);
    } catch (_) {
      return null;
    }
    if (decoded is! Map) return null;
    final map = decoded.cast<String, dynamic>();
    final type = map['type'];
    if (type is! String || type.isEmpty) return null;
    final data = map['data'];
    return ChatFrame(type, data is Map ? data.cast<String, dynamic>() : const {});
  }

  /// message / ack / recall 的 data 是 ChatMessageVO；id 为 0 视为非法帧。
  ChatMessage? get asMessage {
    if (type != ChatFrameType.message &&
        type != ChatFrameType.ack &&
        type != ChatFrameType.recall) {
      return null;
    }
    final msg = ChatMessage.fromJson(data);
    return msg.id == 0 ? null : msg;
  }

  int get sessionId => (data['sessionId'] as num?)?.toInt() ?? 0;

  String get clientMsgId => data['clientMsgId']?.toString() ?? '';

  int get code => (data['code'] as num?)?.toInt() ?? 0;

  String get errorMessage => data['message']?.toString() ?? '';

  int get userId => (data['userId'] as num?)?.toInt() ?? 0;

  @override
  String toString() => 'ChatFrame(' + type + ')';
}
