import 'dart:async';
import 'dart:math';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';

import '../../core/network/api_client.dart';
import '../../core/network/chat_socket.dart';
import '../../core/providers.dart';
import '../../core/utils/formatters.dart';
import '../../data/models2.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/net_image.dart';

class ChatPage extends ConsumerStatefulWidget {
  const ChatPage({super.key, required this.session});

  final ChatSession session;

  @override
  ConsumerState<ChatPage> createState() => _ChatPageState();
}

class _ChatPageState extends ConsumerState<ChatPage> {
  final _input = TextEditingController();
  final _scroll = ScrollController();
  final _messages = <ChatMessage>[];
  ChatSocket? _socket;
  StreamSubscription<Map<String, dynamic>>? _socketSub;
  Timer? _poll;
  List<String> _quickReplies = const [];
  bool _loading = true;
  bool _sending = false;
  bool _online = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      await _load();
      await _loadQuickReplies();
      _connectSocket();
      // WebSocket 断线时靠轮询兜底（5 秒一次，只在页面可见时跑）
      _poll = Timer.periodic(const Duration(seconds: 5), (_) {
        if (!_online) _load(silent: true);
      });
    });
  }

  @override
  void dispose() {
    _poll?.cancel();
    _socketSub?.cancel();
    _socket?.dispose();
    _input.dispose();
    _scroll.dispose();
    super.dispose();
  }

  Future<void> _loadQuickReplies() async {
    try {
      final list = await ref.read(chatRepositoryProvider).quickReplies();
      if (mounted) setState(() => _quickReplies = list);
    } catch (_) {
      // 快捷话术拿不到不影响聊天
    }
  }

  Future<void> _load({bool silent = false}) async {
    if (!silent) setState(() => _loading = true);
    try {
      final list = await ref
          .read(chatRepositoryProvider)
          .messages(widget.session.id, size: 50);
      if (!mounted) return;
      // 后端返回倒序（新→旧），界面要正序
      final ordered = list.reversed.toList();
      setState(() {
        _messages
          ..clear()
          ..addAll(ordered);
        _loading = false;
        _error = null;
      });
      await ref.read(chatRepositoryProvider).markRead(widget.session.id);
      await ref.read(unreadProvider.notifier).refresh();
      _scrollToBottom();
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        if (!silent) _error = e.toString();
      });
    }
  }

  Future<void> _connectSocket() async {
    final config = ref.read(activeServerProvider);
    final token = ref.read(localStoreProvider).token;
    if (config == null || token == null || token.isEmpty) return;
    final socket = ChatSocket(config, token);
    _socket = socket;
    _socketSub = socket.messages.listen(_onSocketMessage);
    socket.connected.listen((ok) {
      if (mounted) setState(() => _online = ok);
    });
    await socket.connect();
  }

  void _onSocketMessage(Map<String, dynamic> raw) {
    // 只处理本会话的包
    final sessionId = (raw['sessionId'] as num?)?.toInt();
    if (sessionId != null && sessionId != widget.session.id) return;
    final msg = ChatMessage.fromJson(raw);
    if (msg.id == 0) return;
    if (!mounted) return;
    setState(() {
      if (_messages.any((m) => m.id == msg.id)) return;
      // 自己发的消息用 clientMsgId 找到本地那条替换掉
      final localIndex = msg.clientMsgId.isEmpty
          ? -1
          : _messages.indexWhere((m) => m.clientMsgId == msg.clientMsgId);
      if (localIndex >= 0) {
        _messages[localIndex] = msg;
      } else {
        _messages.add(msg);
      }
    });
    _scrollToBottom();
  }

  void _scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!_scroll.hasClients) return;
      _scroll.animateTo(
        _scroll.position.maxScrollExtent,
        duration: const Duration(milliseconds: 200),
        curve: Curves.easeOut,
      );
    });
  }

  Future<void> _send({String? text, String? imagePath}) async {
    final content = text ?? '';
    if (content.trim().isEmpty && imagePath == null) return;
    if (_sending) return;
    setState(() => _sending = true);

    final clientMsgId = _newClientId();
    final optimistic = ChatMessage(
      sessionId: widget.session.id,
      msgType: imagePath == null ? 1 : 2,
      content: imagePath == null ? content.trim() : '[图片]',
      mine: true,
      clientMsgId: clientMsgId,
      createdAt: DateTime.now().toIso8601String(),
      localStatus: 'sending',
    );
    setState(() {
      _messages.add(optimistic);
      _input.clear();
    });
    _scrollToBottom();

    try {
      var payload = content.trim();
      var msgType = 1;
      if (imagePath != null) {
        final url = await ref.read(itemWriteRepositoryProvider).upload(imagePath, biz: 'chat');
        if (url.isEmpty) throw ApiException(-1, '图片上传失败');
        payload = url;
        msgType = 2;
      }
      final saved = await ref.read(chatRepositoryProvider).send(
            sessionId: widget.session.id,
            content: payload,
            msgType: msgType,
            clientMsgId: clientMsgId,
          );
      if (!mounted) return;
      setState(() {
        final i = _messages.indexWhere((m) => m.clientMsgId == clientMsgId);
        if (i >= 0) _messages[i] = saved;
        _sending = false;
      });
      _scrollToBottom();
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        final i = _messages.indexWhere((m) => m.clientMsgId == clientMsgId);
        if (i >= 0) _messages[i] = _messages[i].copyWith(localStatus: 'failed');
        _sending = false;
      });
      // 风控拦截（50002）等都会走到这里，直接把后端文案弹出来
      showToast(context, e.message);
    } catch (e) {
      if (!mounted) return;
      setState(() => _sending = false);
      showToast(context, '发送失败：' + e.toString());
    }
  }

  String _newClientId() =>
      DateTime.now().millisecondsSinceEpoch.toString() +
      '-' +
      Random().nextInt(99999).toString();

  Future<void> _pickImage() async {
    final picked = await ImagePicker().pickImage(
      source: ImageSource.gallery,
      imageQuality: 85,
      maxWidth: 1600,
    );
    if (picked == null) return;
    await _send(imagePath: picked.path);
  }

  @override
  Widget build(BuildContext context) {
    final s = widget.session;
    return Scaffold(
      backgroundColor: AppColors.bg,
      appBar: AppBar(
        titleSpacing: 0,
        title: Row(
          children: [
            Avatar(url: s.peerAvatar, name: s.peerName, size: 30),
            const SizedBox(width: 8),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    s.peerName.isEmpty ? '对方' : s.peerName,
                    style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  Text(
                    _online ? '在线 · 实时接收' : '离线 · 轮询接收',
                    style: TextStyle(
                      fontSize: 11,
                      color: _online ? AppColors.brand : AppColors.inkFaint,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
      body: Column(
        children: [
          if (s.itemTitle.isNotEmpty) _itemBanner(s),
          if (s.blockedByMe || s.blockedByPeer || !s.canSend) _blockedBanner(s),
          Expanded(child: _messageList()),
          if (_quickReplies.isNotEmpty && s.canSend) _quickReplyBar(),
          _composer(enabled: s.canSend),
        ],
      ),
    );
  }

  Widget _itemBanner(ChatSession s) {
    return Container(
      color: Colors.white,
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
      child: Row(
        children: [
          NetImage(url: s.itemCover, width: 36, height: 36, radius: 8),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              s.itemTitle,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w500),
            ),
          ),
          PriceText(value: Fmt.price(s.itemPrice), size: 15),
        ],
      ),
    );
  }

  Widget _blockedBanner(ChatSession s) {
    return Container(
      width: double.infinity,
      color: const Color(0xFFFFF4F1),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      child: Text(
        s.blockedByMe ? '你已拉黑对方，解除后才能继续聊天' : '对方已拉黑你，暂时发不出消息',
        style: const TextStyle(fontSize: 12, color: AppColors.price),
      ),
    );
  }

  Widget _messageList() {
    if (_loading) return const Center(child: CircularProgressIndicator());
    if (_messages.isEmpty) {
      return EmptyState(
        icon: Icons.chat_outlined,
        message: _error ?? '还没有消息，先打个招呼吧',
        onRetry: _error == null ? null : () => _load(),
      );
    }
    return ListView.builder(
      controller: _scroll,
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
      itemCount: _messages.length,
      itemBuilder: (_, i) {
        final m = _messages[i];
        final showTime = i == 0 || _timeGap(_messages[i - 1], m);
        return Column(
          children: [
            if (showTime)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 8),
                child: Text(
                  Fmt.timeAgo(m.createdAt),
                  style: const TextStyle(fontSize: 11, color: AppColors.inkFaint),
                ),
              ),
            _bubble(m),
          ],
        );
      },
    );
  }

  bool _timeGap(ChatMessage prev, ChatMessage next) {
    final a = DateTime.tryParse(prev.createdAt);
    final b = DateTime.tryParse(next.createdAt);
    if (a == null || b == null) return false;
    return b.difference(a).inMinutes.abs() >= 5;
  }

  Widget _bubble(ChatMessage m) {
    final mine = m.mine;
    if (m.recalled) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 6),
        child: Center(
          child: Text(
            mine ? '你撤回了一条消息' : '对方撤回了一条消息',
            style: const TextStyle(fontSize: 11.5, color: AppColors.inkFaint),
          ),
        ),
      );
    }
    final bubble = Container(
      constraints: BoxConstraints(maxWidth: MediaQuery.of(context).size.width * 0.66),
      padding: m.isImage ? const EdgeInsets.all(4) : const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
      decoration: BoxDecoration(
        color: mine ? AppColors.brand : Colors.white,
        borderRadius: BorderRadius.circular(12),
      ),
      child: m.isImage
          ? NetImage(url: m.content, width: 150, height: 150, radius: 9)
          : Text(
              m.content,
              style: TextStyle(
                fontSize: 14.5,
                height: 1.45,
                color: mine ? Colors.white : AppColors.ink,
              ),
            ),
    );
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Row(
        mainAxisAlignment: mine ? MainAxisAlignment.end : MainAxisAlignment.start,
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          if (!mine) ...[
            Avatar(url: widget.session.peerAvatar, name: widget.session.peerName, size: 28),
            const SizedBox(width: 8),
          ],
          Column(
            crossAxisAlignment: mine ? CrossAxisAlignment.end : CrossAxisAlignment.start,
            children: [
              bubble,
              if (m.riskWarning.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(top: 3, left: 4, right: 4),
                  child: Text(
                    m.riskWarning,
                    style: const TextStyle(fontSize: 10.5, color: AppColors.price),
                  ),
                ),
              if (mine)
                Padding(
                  padding: const EdgeInsets.only(top: 3, right: 2),
                  child: Text(
                    m.localStatus == 'sending'
                        ? '发送中…'
                        : (m.localStatus == 'failed' ? '发送失败' : ''),
                    style: TextStyle(
                      fontSize: 10,
                      color: m.localStatus == 'failed' ? AppColors.price : AppColors.inkFaint,
                    ),
                  ),
                ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _quickReplyBar() {
    return SizedBox(
      height: 36,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 12),
        itemCount: _quickReplies.length,
        separatorBuilder: (_, __) => const SizedBox(width: 6),
        itemBuilder: (_, i) => ActionChip(
          label: Text(_quickReplies[i], style: const TextStyle(fontSize: 12)),
          backgroundColor: Colors.white,
          side: BorderSide.none,
          onPressed: () => _send(text: _quickReplies[i]),
        ),
      ),
    );
  }

  Widget _composer({required bool enabled}) {
    return Container(
      padding: EdgeInsets.only(
        left: 8,
        right: 8,
        top: 8,
        bottom: MediaQuery.of(context).padding.bottom + 8,
      ),
      decoration: const BoxDecoration(
        color: Colors.white,
        border: Border(top: BorderSide(color: AppColors.line)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          IconButton(
            onPressed: enabled ? _pickImage : null,
            icon: const Icon(Icons.add_photo_alternate_outlined, size: 22),
            color: AppColors.inkSoft,
            tooltip: '发图片',
          ),
          Expanded(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxHeight: 110),
              child: TextField(
                controller: _input,
                enabled: enabled,
                maxLines: null,
                textInputAction: TextInputAction.send,
                onSubmitted: (v) => _send(text: v),
                style: const TextStyle(fontSize: 14.5),
                decoration: InputDecoration(
                  hintText: enabled ? '说点什么…' : '当前无法发送',
                  isDense: true,
                  fillColor: AppColors.bg,
                  contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(18),
                    borderSide: BorderSide.none,
                  ),
                  enabledBorder: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(18),
                    borderSide: BorderSide.none,
                  ),
                ),
              ),
            ),
          ),
          const SizedBox(width: 6),
          IconButton(
            onPressed: enabled && !_sending ? () => _send(text: _input.text) : null,
            icon: const Icon(Icons.send_rounded, size: 22),
            color: AppColors.brand,
            tooltip: '发送',
          ),
        ],
      ),
    );
  }
}
