import 'dart:convert';

import 'package:ershou_app/core/network/chat_events.dart';
import 'package:flutter_test/flutter_test.dart';

/// 锁死"服务端帧是 `{type, data}` 信封"这条协议。
/// 之前手机端把整帧当消息体，导致 message/ack/recall 全被静默丢弃。
void main() {
  String frame(String type, [Map<String, dynamic>? data]) =>
      jsonEncode({'type': type, 'data': data ?? const <String, dynamic>{}});

  final messageJson = <String, dynamic>{
    'id': 1001,
    'sessionId': 7,
    'senderId': 2,
    'receiverId': 1,
    'msgType': 1,
    'content': '在的',
    'mine': false,
    'recalled': false,
    'clientMsgId': null,
    'createdAt': '2026-09-26T14:23:55',
  };

  test('message 帧：从 data 里取消息体，而不是整帧', () {
    final decoded = ChatFrame.decode(frame('message', messageJson));
    expect(decoded, isNotNull);
    expect(decoded!.type, ChatFrameType.message);
    final msg = decoded.asMessage;
    expect(msg, isNotNull);
    expect(msg!.id, 1001);
    expect(msg.sessionId, 7);
    expect(msg.content, '在的');
    expect(msg.mine, isFalse);
    expect(msg.localStatus, 'sent');
  });

  test('ack 帧：带 clientMsgId，mine=true', () {
    final decoded = ChatFrame.decode(frame('ack', {
      ...messageJson,
      'mine': true,
      'clientMsgId': 'c-1',
    }));
    final msg = decoded!.asMessage;
    expect(msg!.clientMsgId, 'c-1');
    expect(msg.mine, isTrue);
  });

  test('recall 帧：recalled=true 且 content 为空', () {
    final decoded = ChatFrame.decode(frame('recall', {
      ...messageJson,
      'recalled': true,
      'content': '',
    }));
    final msg = decoded!.asMessage;
    expect(msg!.recalled, isTrue);
    expect(msg.content, isEmpty);
  });

  test('ready / pong / read / read-ack / error 都按 type 识别', () {
    expect(ChatFrame.decode(frame('ready', {'userId': 9, 'online': true}))!.type,
        ChatFrameType.ready);
    expect(ChatFrame.decode(frame('ready', {'userId': 9}))!.userId, 9);
    expect(ChatFrame.decode(frame('pong', {'ts': 1}))!.asMessage, isNull);
    expect(ChatFrame.decode(frame('read', {'sessionId': 3, 'readerId': 2}))!.sessionId, 3);
    expect(ChatFrame.decode(frame('read-ack', {'sessionId': 3}))!.type, ChatFrameType.readAck);

    final error = ChatFrame.decode(frame('error', {
      'clientMsgId': 'c-9',
      'code': 50002,
      'message': '消息包含站外引流信息',
    }))!;
    expect(error.clientMsgId, 'c-9');
    expect(error.code, 50002);
    expect(error.errorMessage, '消息包含站外引流信息');
  });

  test('非法帧返回 null（不抛异常）', () {
    expect(ChatFrame.decode(''), isNull);
    expect(ChatFrame.decode('not json'), isNull);
    expect(ChatFrame.decode('[1,2,3]'), isNull);
    expect(ChatFrame.decode(jsonEncode({'data': {}})), isNull); // 缺 type
    expect(ChatFrame.decode(null), isNull);
    // data 缺失也不该炸
    expect(ChatFrame.decode(jsonEncode({'type': 'pong'})), isNotNull);
  });

  test('message 帧没有 id 时视为非法（id=0 不落到列表里）', () {
    final decoded = ChatFrame.decode(frame('message', {'sessionId': 7}));
    expect(decoded!.asMessage, isNull);
  });
}
