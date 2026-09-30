import 'package:ershou_app/core/storage/chat_cache.dart';
import 'package:ershou_app/data/models2.dart';
import 'package:flutter_test/flutter_test.dart';

ChatMessage server({
  required int id,
  int sessionId = 7,
  String content = '',
  bool mine = false,
  bool recalled = false,
  String clientMsgId = '',
  String createdAt = '2026-09-26T14:00:00',
}) =>
    ChatMessage(
      id: id,
      sessionId: sessionId,
      msgType: 1,
      content: content,
      mine: mine,
      recalled: recalled,
      clientMsgId: clientMsgId,
      createdAt: createdAt,
    );

ChatMessage local({
  required String clientMsgId,
  String content = 'hi',
  String localStatus = 'sending',
  String createdAt = '2026-09-26T14:05:00',
}) =>
    ChatMessage(
      sessionId: 7,
      msgType: 1,
      content: content,
      mine: true,
      clientMsgId: clientMsgId,
      createdAt: createdAt,
      localStatus: localStatus,
    );

void main() {
  group('mergeMessages', () {
    test('服务端消息按 id 覆盖本地旧值（撤回态以服务端为准）', () {
      final merged = mergeMessages(
        local: [server(id: 1, content: '原文')],
        incoming: [server(id: 1, content: '', recalled: true)],
      );
      expect(merged.length, 1);
      expect(merged.first.recalled, isTrue);
      expect(merged.first.content, isEmpty);
    });

    test('ack 用 clientMsgId 替换本地"发送中"的乐观消息', () {
      final merged = mergeMessages(
        local: [local(clientMsgId: 'c-1')],
        incoming: [server(id: 5, content: 'hi', mine: true, clientMsgId: 'c-1')],
      );
      expect(merged.length, 1);
      expect(merged.first.id, 5);
      expect(merged.first.localStatus, 'sent');
    });

    test('本地没回包/发送失败的消息不会被网络刷新冲掉', () {
      final merged = mergeMessages(
        local: [
          local(clientMsgId: 'c-1', localStatus: 'failed', createdAt: '2026-09-26T14:05:00'),
        ],
        incoming: [server(id: 3, content: '对方的话')],
      );
      expect(merged.length, 2);
      expect(merged.where((m) => m.localStatus == 'failed').length, 1);
    });

    test('结果按时间正序，本地未落库消息排最后', () {
      final merged = mergeMessages(
        local: [local(clientMsgId: 'c-2', createdAt: '2026-09-26T14:05:00')],
        incoming: [
          server(id: 9, content: '晚', createdAt: '2026-09-26T14:00:00'),
          server(id: 4, content: '早', createdAt: '2026-09-26T13:00:00'),
        ],
      );
      expect(merged.map((m) => m.content).toList(), ['早', '晚', 'hi']);
    });

    test('服务端返回乱序也能排好', () {
      final merged = mergeMessages(
        local: const [],
        incoming: [
          server(id: 2, content: 'b', createdAt: '2026-09-26T14:00:02'),
          server(id: 1, content: 'a', createdAt: '2026-09-26T14:00:01'),
        ],
      );
      expect(merged.map((m) => m.content).toList(), ['a', 'b']);
    });
  });

  group('mergeSessions', () {
    ChatSession session(int id, String lastMsgAt, {int unread = 0}) => ChatSession(
          id: id,
          peerName: 'peer' + id.toString(),
          lastMsgAt: lastMsgAt,
          unread: unread,
        );

    test('服务端覆盖同 id，本地新会话保留，按最后消息时间倒序', () {
      final merged = mergeSessions(
        local: [session(1, '2026-09-26T10:00:00', unread: 3), session(2, '2026-09-26T12:00:00')],
        incoming: [session(1, '2026-09-26T11:00:00', unread: 0)],
      );
      expect(merged.length, 2);
      expect(merged.first.id, 2);
      expect(merged.last.unread, 0);
    });
  });

  group('chatScopeOf', () {
    test('服务器 + 用户 id 组成命名空间', () {
      expect(chatScopeOf(origin: 'http://10.0.0.2:8080', userId: 10001),
          'http://10.0.0.2:8080|10001');
    });

    test('缺服务器或用户 id 时返回空串（= 不缓存）', () {
      expect(chatScopeOf(origin: '', userId: 10001), isEmpty);
      expect(chatScopeOf(origin: 'http://10.0.0.2:8080', userId: 0), isEmpty);
    });

    test('同一用户换服务器 = 不同命名空间（不串会话）', () {
      expect(chatScopeOf(origin: 'http://a:8080', userId: 1),
          isNot(chatScopeOf(origin: 'http://b:8080', userId: 1)));
    });
  });
}
