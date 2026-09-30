import 'dart:async';
import 'dart:math';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';

import '../../core/network/api_client.dart';
import '../../core/network/chat_events.dart';
import '../../core/providers.dart';
import '../../core/storage/chat_cache.dart';
import '../../core/utils/formatters.dart';
import '../../data/models2.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/net_image.dart';

/// 聊天窗：本地缓存先渲染 → 网络刷新 → WS 事件增量更新。
///
/// 这里的三个"曾经踩过的坑"都在代码里留了注释：
///  1. 服务端帧是 {type, data} 信封，必须按 type 分发（见 ChatFrame）；
///  2. 轮询兜底不能只看"连上了没有"，还要看"多久没收到帧了"；
///  3. 乐观消息、失败消息不能被一次网络刷新冲掉（见 mergeMessages）。
class ChatPage extends ConsumerStatefulWidget {
  const ChatPage({super.key, required this.session});

  final ChatSession session;

  @override
  ConsumerState<ChatPage> createState() => _ChatPageState();
}

class _ChatPageState extends ConsumerState<ChatPage> {
  static const _pageSize = 50;
  static const _historySize = 30;
  static const _recallWindow = Duration(minutes: 2);

  final _input = TextEditingController();
  final _scroll = ScrollController();

  List<ChatMessage> _messages = const [];
  StreamSubscription<ChatFrame>? _frameSub;
  StreamSubscription<bool>? _statusSub;
  Timer? _poll;
  List<String> _quickReplies = const [];

  late final ChatDraftsNotifier _drafts;
  late final ActiveChatSessionNotifier _activeSession;

  bool _loading = true;
  bool _sending = false;
  bool _online = false;
  bool _loadingHistory = false;
  bool _hasMoreHistory = true;
  bool _fromCache = false;
  String? _error;

  int get _sessionId => widget.session.id;

  @override
  void initState() {
    super.initState();
    _drafts = ref.read(chatDraftsProvider.notifier);
    _activeSession = ref.read(activeChatSessionProvider.notifier);
    _scroll.addListener(_onScroll);
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      _activeSession.enter(_sessionId);
      _input.text = _drafts.take(_sessionId);
      _bindSocket();
      await _loadFromCache();
      await _load();
      await _loadQuickReplies();
      _startPolling();
    });
  }

  @override
  void dispose() {
    _drafts.save(_sessionId, _input.text);
    _activeSession.leave(_sessionId);
    _poll?.cancel();
    _frameSub?.cancel();
    _statusSub?.cancel();
    _input.dispose();
    _scroll.dispose();
    super.dispose();
  }

  // ------------------------------------------------------------ 连接与事件

  void _bindSocket() {
    final socket = ref.read(chatSocketProvider);
    _online = socket.isOnline;
    _statusSub = socket.connected.listen((ok) {
      if (mounted) setState(() => _online = ok);
    });
    _frameSub = socket.frames.listen(_onFrame);
  }

  void _startPolling() {
    _poll = Timer.periodic(const Duration(seconds: 5), (_) {
      if (!mounted) return;
      // 被别的页面盖住时不做无谓请求
      final route = ModalRoute.of(context);
      if (route != null && !route.isCurrent) return;
      final socket = ref.read(chatSocketProvider);
      // WS 健康（在线且 20 秒内收到过帧）就不轮询；连上但半死时照样兜底
      if (socket.isOnline && socket.silence < const Duration(seconds: 20)) return;
      unawaited(_load(silent: true));
    });
  }

  /// 帧分发：信封里的 type 决定怎么处理，message/ack/recall 的 data 才是消息体。
  void _onFrame(ChatFrame frame) {
    if (!mounted) return;
    switch (frame.type) {
      case ChatFrameType.ready:
        // 重连成功：用 afterId 增量补拉断线期间的消息（见 _fillGap）
        unawaited(_fillGap());
        return;
      case ChatFrameType.message:
      case ChatFrameType.ack:
        final msg = frame.asMessage;
        if (msg == null || msg.sessionId != _sessionId) return;
        _upsertMessage(msg);
        return;
      case ChatFrameType.recall:
        final msg = frame.asMessage;
        if (msg == null || msg.sessionId != _sessionId) return;
        final index = _messages.indexWhere((m) => m.id == msg.id);
        if (index >= 0) {
          setState(() => _messages = _replaceAt(_messages, index, msg));
        }
        return;
      case ChatFrameType.error:
        // 发送被业务拒绝（限额/风控/无权）：只有本机发出去的才有 clientMsgId
        final clientMsgId = frame.clientMsgId;
        if (clientMsgId.isEmpty) return;
        final index = _messages.indexWhere((m) => m.clientMsgId == clientMsgId);
        if (index < 0) return;
        setState(() {
          _messages = _replaceAt(
            _messages,
            index,
            _messages[index].copyWith(localStatus: 'failed'),
          );
          _sending = false;
        });
        if (frame.errorMessage.isNotEmpty) showToast(context, frame.errorMessage);
        return;
      default:
        return;
    }
  }

  void _upsertMessage(ChatMessage msg) {
    final byId = msg.fromServer ? _messages.indexWhere((m) => m.id == msg.id) : -1;
    final byClient = msg.clientMsgId.isEmpty
        ? -1
        : _messages.indexWhere((m) => !m.fromServer && m.clientMsgId == msg.clientMsgId);
    final index = byId >= 0 ? byId : byClient;
    setState(() {
      _messages = index >= 0
          ? _replaceAt(_messages, index, msg)
          : <ChatMessage>[..._messages, msg];
      _fromCache = false;
    });
    _scrollToBottom();
  }

  static List<ChatMessage> _replaceAt(List<ChatMessage> list, int index, ChatMessage value) {
    final next = [...list];
    next[index] = value;
    return next;
  }

  // ------------------------------------------------------------ 载入

  /// 先用本地镜像出内容（秒开），网络回来再覆盖。
  Future<void> _loadFromCache() async {
    final cache = ref.read(chatCacheProvider);
    final scope = ref.read(chatScopeProvider);
    if (cache == null || scope.isEmpty) return;
    try {
      // 超过 30 秒没回包的"发送中"直接判失败，别让用户看着它一直转
      await cache.failStaleSending(scope);
      final cached = await cache.messages(scope, _sessionId);
      if (!mounted || cached.isEmpty) return;
      setState(() {
        _messages = mergeMessages(local: cached, incoming: _messages);
        _loading = false;
        _fromCache = true;
      });
      _jumpToBottom();
    } catch (_) {
      // 缓存读失败不影响网络加载
    }
  }

  Future<void> _load({bool silent = false}) async {
    if (!silent && _messages.isEmpty) setState(() => _loading = true);
    try {
      final list = await ref.read(chatRepositoryProvider).messages(_sessionId, size: _pageSize);
      if (!mounted) return;
      final ordered = list.reversed.toList();
      setState(() {
        _messages = mergeMessages(local: _messages, incoming: ordered);
        _loading = false;
        _error = null;
        _fromCache = false;
        if (list.length < _pageSize) _hasMoreHistory = false;
      });
      _writeCache(ordered);
      await _markRead();
      _scrollToBottom();
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        if (!silent) _error = e.toString();
      });
    }
  }

  /// 重连后的增量补拉：只取本地最后一条之后的消息（后端 afterId）。
  ///
  /// 三种回退，保证后端没升级时也不会漏消息：
  ///  - 本地没有服务端消息（新会话/第一次进来）→ 全量拉最近 N 条；
  ///  - 增量返回满一页（断线期间超过一页，中间会有空洞）→ 全量刷新；
  ///  - 增量接口报错（老后端不认 afterId）→ 全量刷新。
  Future<void> _fillGap() async {
    final cache = ref.read(chatCacheProvider);
    final scope = ref.read(chatScopeProvider);
    var afterId = 0;
    if (cache != null && scope.isNotEmpty) {
      try {
        afterId = await cache.maxServerId(scope, _sessionId);
      } catch (_) {
        afterId = 0;
      }
    }
    if (afterId <= 0) {
      await _load(silent: true);
      return;
    }
    try {
      final list = await ref
          .read(chatRepositoryProvider)
          .messages(_sessionId, afterId: afterId, size: _pageSize);
      if (!mounted) return;
      if (list.isEmpty) return;
      if (list.length >= _pageSize) {
        await _load(silent: true);
        return;
      }
      final ordered = list.reversed.toList();
      setState(() {
        _messages = mergeMessages(local: _messages, incoming: ordered);
        _fromCache = false;
      });
      _writeCache(ordered);
      await _markRead();
      _scrollToBottom();
    } catch (_) {
      await _load(silent: true);
    }
  }

  /// 上拉/滚到顶部加载更早的历史（beforeId 游标）。
  Future<void> _loadHistory() async {
    if (_loadingHistory || !_hasMoreHistory) return;
    var beforeId = 0;
    for (final m in _messages) {
      if (m.fromServer) {
        beforeId = m.id;
        break;
      }
    }
    if (beforeId <= 0) return;
    final previousMax = _scroll.hasClients ? _scroll.position.maxScrollExtent : 0.0;
    final previousPixels = _scroll.hasClients ? _scroll.position.pixels : 0.0;
    setState(() => _loadingHistory = true);
    try {
      final older = await ref
          .read(chatRepositoryProvider)
          .messages(_sessionId, beforeId: beforeId, size: _historySize);
      if (!mounted) return;
      final ordered = older.reversed.toList();
      setState(() {
        _messages = mergeMessages(local: _messages, incoming: ordered);
        _loadingHistory = false;
        if (older.length < _historySize) _hasMoreHistory = false;
      });
      _writeCache(ordered);
      // 前面插了内容，把视口钉回原来的位置，避免"跳一下"
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!_scroll.hasClients) return;
        final delta = _scroll.position.maxScrollExtent - previousMax;
        if (delta > 0) _scroll.jumpTo(previousPixels + delta);
      });
    } catch (_) {
      if (mounted) setState(() => _loadingHistory = false);
    }
  }

  Future<void> _loadQuickReplies() async {
    try {
      final list = await ref.read(chatRepositoryProvider).quickReplies();
      if (mounted) setState(() => _quickReplies = list);
    } catch (_) {
      // 快捷话术拿不到不影响聊天
    }
  }

  void _writeCache(List<ChatMessage> list) {
    final cache = ref.read(chatCacheProvider);
    final scope = ref.read(chatScopeProvider);
    if (cache == null || scope.isEmpty || list.isEmpty) return;
    unawaited(cache.saveMessages(scope, _sessionId, list));
  }

  Future<void> _markRead() async {
    try {
      await ref.read(chatRepositoryProvider).markRead(_sessionId);
      await ref.read(chatCacheProvider)?.markRead(ref.read(chatScopeProvider), _sessionId);
    } catch (_) {
      // 已读失败不影响阅读；下次进页面会再标一次
    }
    await ref.read(unreadProvider.notifier).refresh();
  }

  // ------------------------------------------------------------ 发送

  Future<void> _send({String? text, String? imagePath}) async {
    final content = (text ?? '').trim();
    if (content.isEmpty && imagePath == null) return;
    if (_sending) return;

    final optimistic = ChatMessage(
      sessionId: _sessionId,
      msgType: imagePath == null ? 1 : 2,
      content: imagePath == null ? content : '[图片]',
      mine: true,
      clientMsgId: _newClientId(),
      createdAt: DateTime.now().toIso8601String(),
      localStatus: 'sending',
    );
    setState(() {
      _messages = <ChatMessage>[..._messages, optimistic];
      _input.clear();
    });
    _drafts.save(_sessionId, '');
    _scrollToBottom();
    await _deliver(optimistic, imagePath: imagePath);
  }

  Future<void> _deliver(ChatMessage optimistic, {String? imagePath}) async {
    final clientMsgId = optimistic.clientMsgId;
    setState(() => _sending = true);
    _writeCache([optimistic]);
    try {
      var payload = optimistic.content;
      if (imagePath != null) {
        final url = await ref.read(itemWriteRepositoryProvider).upload(imagePath, biz: 'chat');
        if (url.isEmpty) throw ApiException(-1, '图片上传失败');
        payload = url;
      }
      final saved = await ref.read(chatRepositoryProvider).send(
            sessionId: _sessionId,
            content: payload,
            msgType: optimistic.msgType,
            clientMsgId: clientMsgId,
          );
      if (!mounted) return;
      final index = _messages.indexWhere((m) => m.clientMsgId == clientMsgId);
      setState(() {
        if (index >= 0) _messages = _replaceAt(_messages, index, saved);
        _sending = false;
      });
      ref.read(peerReadProvider.notifier).markUnread(_sessionId);
      _writeCache([saved]);
      _scrollToBottom();
    } on ApiException catch (e) {
      _markFailed(clientMsgId);
      if (mounted) showToast(context, e.message);
    } catch (e) {
      _markFailed(clientMsgId);
      if (mounted) showToast(context, '发送失败：' + e.toString());
    }
  }

  void _markFailed(String clientMsgId) {
    if (!mounted) return;
    final index = _messages.indexWhere((m) => m.clientMsgId == clientMsgId);
    if (index < 0) return;
    setState(() {
      _messages = _replaceAt(
        _messages,
        index,
        _messages[index].copyWith(localStatus: 'failed'),
      );
      _sending = false;
    });
    final cache = ref.read(chatCacheProvider);
    final scope = ref.read(chatScopeProvider);
    if (cache != null && scope.isNotEmpty) {
      unawaited(cache.setLocalStatus(scope, 'c:' + clientMsgId, 'failed'));
    }
  }

  /// 点一下失败的消息重发（图片消息没有本地路径，只能重选图片）。
  Future<void> _retry(ChatMessage msg) async {
    if (_sending || msg.fromServer) return;
    if (msg.msgType != 1) {
      showToast(context, '图片消息请重新选择图片发送');
      return;
    }
    final index = _messages.indexWhere((m) => m.clientMsgId == msg.clientMsgId);
    if (index < 0) return;
    final sending = msg.copyWith(localStatus: 'sending');
    setState(() => _messages = _replaceAt(_messages, index, sending));
    await _deliver(sending);
  }

  /// 撤回：仅自己发的、服务端已落库、2 分钟内的消息。
  Future<void> _recall(ChatMessage msg) async {
    if (!msg.mine || !msg.fromServer || msg.recalled) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        content: const Text('撤回这条消息？'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('取消')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('撤回')),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    try {
      final recalled = await ref.read(chatRepositoryProvider).recall(msg.id);
      if (!mounted) return;
      final index = _messages.indexWhere((m) => m.id == msg.id);
      if (index >= 0) setState(() => _messages = _replaceAt(_messages, index, recalled));
      unawaited(ref.read(chatCacheProvider)?.markRecalled(ref.read(chatScopeProvider), msg.id));
    } on ApiException catch (e) {
      if (mounted) showToast(context, e.message);
    }
  }

  bool _canRecall(ChatMessage msg) {
    if (!msg.mine || !msg.fromServer || msg.recalled) return false;
    final at = DateTime.tryParse(msg.createdAt);
    if (at == null) return true;
    return DateTime.now().difference(at).abs() <= _recallWindow;
  }

  Future<void> _pickImage() async {
    final picked = await ImagePicker().pickImage(
      source: ImageSource.gallery,
      imageQuality: 85,
      maxWidth: 1600,
    );
    if (picked == null) return;
    await _send(imagePath: picked.path);
  }

  String _newClientId() =>
      DateTime.now().millisecondsSinceEpoch.toString() +
      '-' +
      Random().nextInt(99999).toString();

  // ------------------------------------------------------------ 滚动

  void _onScroll() {
    if (!_scroll.hasClients) return;
    if (_scroll.position.pixels <= 60) unawaited(_loadHistory());
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

  void _jumpToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!_scroll.hasClients) return;
      _scroll.jumpTo(_scroll.position.maxScrollExtent);
    });
  }

  // ------------------------------------------------------------ 渲染

  @override
  Widget build(BuildContext context) {
    final s = widget.session;
    final peerRead = ref.watch(peerReadProvider).contains(_sessionId);
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
                    _statusText(),
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
          if (_fromCache && !_online) _cacheBanner(),
          Expanded(child: _messageList(peerRead)),
          if (_quickReplies.isNotEmpty && s.canSend) _quickReplyBar(),
          _composer(enabled: s.canSend),
        ],
      ),
    );
  }

  String _statusText() {
    if (_online) return '在线 · 实时接收';
    return _fromCache ? '离线 · 显示本地缓存' : '离线 · 轮询接收';
  }

  Widget _cacheBanner() {
    return Container(
      width: double.infinity,
      color: const Color(0xFFFFF8E6),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      child: const Text(
        '当前网络不可用，下面是本地缓存的消息',
        style: TextStyle(fontSize: 11.5, color: Color(0xFF9A6B00)),
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

  Widget _messageList(bool peerRead) {
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
      itemCount: _messages.length + 1,
      itemBuilder: (_, i) {
        if (i == 0) return _historyHeader();
        final index = i - 1;
        final m = _messages[index];
        final showTime = index == 0 || _timeGap(_messages[index - 1], m);
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
            _bubble(
              m,
              isLast: index == _messages.length - 1,
              peerRead: peerRead,
            ),
          ],
        );
      },
    );
  }

  Widget _historyHeader() {
    if (_loadingHistory) {
      return const Padding(
        padding: EdgeInsets.symmetric(vertical: 10),
        child: Center(
          child: SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2)),
        ),
      );
    }
    if (!_hasMoreHistory) {
      return const Padding(
        padding: EdgeInsets.symmetric(vertical: 10),
        child: Center(
          child: Text('— 没有更早的消息了 —',
              style: TextStyle(fontSize: 11, color: AppColors.inkFaint)),
        ),
      );
    }
    return const SizedBox(height: 4);
  }

  bool _timeGap(ChatMessage prev, ChatMessage next) {
    final a = DateTime.tryParse(prev.createdAt);
    final b = DateTime.tryParse(next.createdAt);
    if (a == null || b == null) return false;
    return b.difference(a).inMinutes.abs() >= 5;
  }

  Widget _bubble(ChatMessage m, {required bool isLast, required bool peerRead}) {
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
      padding: m.isImage
          ? const EdgeInsets.all(4)
          : const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
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
    final status = _statusLabel(m, isLast: isLast, peerRead: peerRead);
    final child = Row(
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
            if (status.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 3, right: 2),
                child: Text(
                  status,
                  style: TextStyle(
                    fontSize: 10,
                    color: m.localStatus == 'failed' ? AppColors.price : AppColors.inkFaint,
                  ),
                ),
              ),
          ],
        ),
      ],
    );
    if (!mine) return Padding(padding: const EdgeInsets.only(bottom: 10), child: child);
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: GestureDetector(
        onLongPress: _canRecall(m) ? () => _recall(m) : null,
        onTap: m.localStatus == 'failed' ? () => _retry(m) : null,
        child: child,
      ),
    );
  }

  String _statusLabel(ChatMessage m, {required bool isLast, required bool peerRead}) {
    if (m.localStatus == 'sending') return '发送中…';
    if (m.localStatus == 'failed') return '发送失败，点击重发';
    if (isLast && peerRead) return '已读';
    return '';
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
