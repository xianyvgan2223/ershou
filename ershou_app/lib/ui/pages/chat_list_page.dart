import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/providers.dart';
import '../../core/storage/chat_cache.dart';
import '../../core/utils/formatters.dart';
import '../../data/models2.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/net_image.dart';
import 'chat_page.dart';

class ChatListPage extends ConsumerStatefulWidget {
  const ChatListPage({super.key});

  @override
  ConsumerState<ChatListPage> createState() => ChatListPageState();
}

class ChatListPageState extends ConsumerState<ChatListPage> {
  List<ChatSession> _sessions = const [];
  bool _loading = true;
  String? _error;
  Timer? _reloadDebounce;

  /// 长按提示条：用户删过一次（说明已经知道怎么操作）就自动收起，不再占地方。
  bool _showGestureHint = true;

  /// 多选删除模式：进入后每行左侧出现勾选框，顶部栏变成"已选 N 条 / 全选 / 删除"。
  bool _selecting = false;
  final Set<int> _selected = <int>{};
  bool _deleting = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => load());
  }

  @override
  void dispose() {
    _reloadDebounce?.cancel();
    super.dispose();
  }

  /// 新消息/撤回/重连都会 bump 版本号：合并成一次刷新，别每条消息都打一遍接口。
  void _scheduleReload() {
    _reloadDebounce?.cancel();
    _reloadDebounce = Timer(const Duration(milliseconds: 600), () {
      if (mounted) unawaited(load(silent: true));
    });
  }

  /// 先用本地镜像出列表（冷启动秒开），再拉服务端覆盖。
  Future<void> load({bool silent = false}) async {
    if (!mounted) return;
    if (!silent && _sessions.isEmpty) {
      setState(() {
        _loading = true;
        _error = null;
      });
    }
    await _loadFromCache();
    try {
      final list = await ref.read(chatRepositoryProvider).sessions();
      if (!mounted) return;
      setState(() {
        _sessions = mergeSessions(local: _sessions, incoming: list);
        _loading = false;
        _error = null;
      });
      final cache = ref.read(chatCacheProvider);
      final scope = ref.read(chatScopeProvider);
      if (cache != null && scope.isNotEmpty) {
        unawaited(cache.saveSessions(scope, list));
      }
      await ref.read(unreadProvider.notifier).refresh();
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        if (_sessions.isEmpty) _error = e.toString();
      });
    }
  }

  Future<void> _loadFromCache() async {
    if (_sessions.isNotEmpty) return;
    final cache = ref.read(chatCacheProvider);
    final scope = ref.read(chatScopeProvider);
    if (cache == null || scope.isEmpty) return;
    try {
      final cached = await cache.sessions(scope);
      if (!mounted || cached.isEmpty) return;
      setState(() {
        _sessions = cached;
        _loading = false;
      });
    } catch (_) {
      // 缓存读失败不影响网络加载
    }
  }

  @override
  Widget build(BuildContext context) {
    // 聊天长连接推来的变化 → 列表自动刷新（以前要手动下拉或退出重进）
    ref.listen(chatRevisionProvider, (_, __) => _scheduleReload());
    return Scaffold(
      backgroundColor: AppColors.bg,
      appBar: _selecting ? _selectionAppBar() : _normalAppBar(),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _sessions.isEmpty
              ? EmptyState(
                  icon: Icons.forum_outlined,
                  message: _error ?? '还没有会话\n去商品详情页点「私聊」就能开始',
                  onRetry: () => load(),
                )
              : RefreshIndicator(
                  onRefresh: () => load(silent: true),
                  child: Column(
                    children: [
                      // 手势提示：长按/多选是个"看不见"的功能，不放提示用户永远发现不了
                      // （之前只在代码注释里写了"入口用长按"，界面上一个字都没有）
                      if (_showGestureHint && !_selecting)
                        Container(
                          width: double.infinity,
                          color: AppColors.brandSoft,
                          padding: const EdgeInsets.fromLTRB(16, 7, 16, 7),
                          child: Row(
                            children: [
                              const Icon(Icons.touch_app_outlined,
                                  size: 14, color: AppColors.brandDark),
                              const SizedBox(width: 6),
                              const Expanded(
                                child: Text(
                                  '长按可多选批量删除；单条删除点右侧「⋯」',
                                  style: TextStyle(fontSize: 11.5, color: AppColors.brandDark),
                                ),
                              ),
                              GestureDetector(
                                onTap: () => setState(() => _showGestureHint = false),
                                child: const Icon(Icons.close,
                                    size: 14, color: AppColors.brandDark),
                              ),
                            ],
                          ),
                        ),
                      Expanded(
                        child: ListView.separated(
                          padding: const EdgeInsets.symmetric(vertical: 6),
                          itemCount: _sessions.length,
                          separatorBuilder: (_, __) => const Divider(
                            height: 1,
                            indent: 76,
                            color: AppColors.line,
                          ),
                          itemBuilder: (_, i) => _tile(_sessions[i]),
                        ),
                      ),
                    ],
                  ),
                ),
    );
  }

  AppBar _normalAppBar() => AppBar(
        title: const Text('消息'),
        actions: [
          IconButton(
            onPressed: () => load(),
            icon: const Icon(Icons.refresh, size: 20),
            tooltip: '刷新',
          ),
        ],
      );

  /// 多选模式的顶部栏：退出 / 已选 N 条 / 全选 / 删除
  AppBar _selectionAppBar() {
    final allSelected = _sessions.isNotEmpty && _selected.length == _sessions.length;
    return AppBar(
      backgroundColor: AppColors.brandSoft,
      foregroundColor: AppColors.brandDark,
      leading: IconButton(
        onPressed: _exitSelection,
        icon: const Icon(Icons.close, size: 21),
        tooltip: '退出多选',
      ),
      title: Text('已选 ${_selected.length} 条', style: const TextStyle(fontSize: 16)),
      actions: [
        TextButton(
          onPressed: _sessions.isEmpty ? null : () => _toggleSelectAll(!allSelected),
          child: Text(
            allSelected ? '取消全选' : '全选',
            style: const TextStyle(color: AppColors.brandDark, fontSize: 13.5),
          ),
        ),
        TextButton.icon(
          onPressed: _selected.isEmpty || _deleting ? null : _deleteSelected,
          icon: _deleting
              ? const SizedBox(
                  width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2))
              : const Icon(Icons.delete_outline, size: 18),
          label: const Text('删除', style: TextStyle(fontSize: 13.5)),
          style: TextButton.styleFrom(
            foregroundColor: AppColors.price,
            disabledForegroundColor: AppColors.inkFaint,
          ),
        ),
      ],
    );
  }

  void _exitSelection() {
    setState(() {
      _selecting = false;
      _selected.clear();
    });
  }

  /// 进入多选模式，并把 [first] 一并勾上（长按哪条就选哪条，少一次点击）
  void _enterSelection(ChatSession first) {
    setState(() {
      _selecting = true;
      _selected
        ..clear()
        ..add(first.id);
    });
  }

  void _toggleSelectOne(int sessionId) {
    setState(() {
      if (!_selected.remove(sessionId)) _selected.add(sessionId);
    });
  }

  void _toggleSelectAll(bool selectAll) {
    setState(() {
      _selected.clear();
      if (selectAll) _selected.addAll(_sessions.map((s) => s.id));
    });
  }

  /// 批量删除：一次请求、逐条独立处理，失败的那几条要如实告诉用户。
  Future<void> _deleteSelected() async {
    // 防重入：确认弹窗期间按钮仍可点，连点会叠出多个弹窗并重复发请求
    if (_deleting) return;
    final ids = _selected.toList();
    if (ids.isEmpty) return;
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('删除聊天'),
        content: Text(
          '删除选中的 ${ids.length} 个会话？\n\n'
          '只删除你这一侧的记录，对方不受影响。\n'
          '对方再发消息时，会话会重新出现。',
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(ctx).pop(false), child: const Text('取消')),
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('删除', style: TextStyle(color: AppColors.price)),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return;

    setState(() => _deleting = true);
    // 先从界面与本地镜像里摘掉：删除要立刻见效，不等服务端回包
    final removing = Set<int>.from(ids);
    setState(() {
      _sessions = _sessions.where((s) => !removing.contains(s.id)).toList();
      _selected.clear();
    });
    final cache = ref.read(chatCacheProvider);
    final scope = ref.read(chatScopeProvider);
    if (cache != null && scope.isNotEmpty) {
      for (final id in ids) {
        unawaited(cache.deleteSession(scope, id));
      }
    }

    try {
      final (success, failures) = await ref.read(chatRepositoryProvider).batchDeleteSessions(ids);
      if (!mounted) return;
      setState(() {
        _deleting = false;
        _selecting = false;
        _showGestureHint = false;
      });
      if (failures.isEmpty) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('已删除 $success 个会话'), duration: const Duration(seconds: 2)),
        );
      } else {
        // 不用 SnackBar 塞长文案：失败原因可能有好几条
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('成功 $success 个，${failures.length} 个没删掉'),
            duration: const Duration(seconds: 5),
            action: SnackBarAction(
              label: '查看',
              onPressed: () => showDialog<void>(
                context: context,
                builder: (ctx) => AlertDialog(
                  title: const Text('未删除的会话'),
                  content: SingleChildScrollView(
                    child: Text(failures.join('\n'), style: const TextStyle(fontSize: 13)),
                  ),
                  actions: [
                    TextButton(
                      onPressed: () => Navigator.of(ctx).pop(),
                      child: const Text('知道了'),
                    ),
                  ],
                ),
              ),
            ),
          ),
        );
      }
    } catch (e) {
      if (!mounted) return;
      setState(() => _deleting = false);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('删除失败：$e'), duration: const Duration(seconds: 3)),
      );
    } finally {
      // 无论成功失败都重拉一次：成功时对齐服务端并更新红点，失败时把行找回来
      await load(silent: true);
    }
  }

  /// 删除聊天记录：先弹确认，再打服务端 + 清本地镜像 + 刷新列表与红点。
  ///
  /// <p>入口用**长按**（和微信一致）：删除是破坏性操作，摊在列表上做成显式按钮
  /// 容易误触；长按 + 二次确认既够隐蔽又够好找。
  Future<void> _confirmDelete(ChatSession s) async {
    final name = s.peerName.isEmpty ? '对方' : s.peerName;
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('删除聊天'),
        content: Text(
          '删除与「$name」的聊天记录？\n\n'
          '只删除你这一侧的记录，不影响对方。\n'
          '对方再发消息时，会话会重新出现。',
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(ctx).pop(false), child: const Text('取消')),
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('删除', style: TextStyle(color: AppColors.price)),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return;

    // 先从界面与本地镜像里摘掉：删除就该立刻生效，不要等接口回来才消失
    setState(() {
      _sessions = _sessions.where((x) => x.id != s.id).toList();
    });
    final cache = ref.read(chatCacheProvider);
    final scope = ref.read(chatScopeProvider);
    if (cache != null && scope.isNotEmpty) {
      unawaited(cache.deleteSession(scope, s.id));
    }
    try {
      await ref.read(chatRepositoryProvider).deleteSession(s.id);
      if (!mounted) return;
      // 用户已经会用这个功能了，提示条收起来别一直占地方
      setState(() => _showGestureHint = false);
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('已删除聊天记录'), duration: Duration(seconds: 2)),
      );
    } catch (e) {
      if (!mounted) return;
      // 服务端失败就把列表拉回来（本地已经摘掉了，不拉会显示成"删掉了其实没删"）
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('删除失败：$e'), duration: const Duration(seconds: 3)),
      );
    } finally {
      // 无论成功失败都重拉一次：成功时对齐服务端（顺带更新红点），失败时把行找回来
      await load(silent: true);
    }
  }

  Widget _tile(ChatSession s) {
    final roleText = s.iAmSeller ? '我卖' : '我买';
    final checked = _selected.contains(s.id);
    return Material(
      color: checked ? AppColors.brandSoft : Colors.white,
      child: InkWell(
        onTap: () async {
          // 多选模式下点整行 = 勾选/取消；要进聊天窗得先退出多选
          if (_selecting) {
            _toggleSelectOne(s.id);
            return;
          }
          await Navigator.of(context).push(
            MaterialPageRoute<void>(builder: (_) => ChatPage(session: s)),
          );
          if (mounted) load(silent: true);
        },
        // 长按进入多选（与微信一致）：一次勾一条，顶部还能"全选"
        onLongPress: () {
          if (_selecting) {
            _toggleSelectOne(s.id);
          } else {
            _enterSelection(s);
          }
        },
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // 多选模式下左侧出现勾选框（整行也可点，勾选框只是视觉提示）
              if (_selecting) ...[
                Padding(
                  padding: const EdgeInsets.only(top: 14, right: 4),
                  child: Icon(
                    checked ? Icons.check_circle : Icons.radio_button_unchecked,
                    size: 21,
                    color: checked ? AppColors.brand : AppColors.inkFaint,
                  ),
                ),
              ],
              Stack(
                clipBehavior: Clip.none,
                children: [
                  Avatar(url: s.peerAvatar, name: s.peerName, size: 46),
                  if (s.peerOnline)
                    Positioned(
                      right: 0,
                      bottom: 0,
                      child: Container(
                        width: 12,
                        height: 12,
                        decoration: BoxDecoration(
                          color: AppColors.brand,
                          shape: BoxShape.circle,
                          border: Border.all(color: Colors.white, width: 2),
                        ),
                      ),
                    ),
                ],
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Flexible(
                          child: Text(
                            s.peerName.isEmpty ? '对方' : s.peerName,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(
                              fontSize: 15,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ),
                        const SizedBox(width: 6),
                        TagChip(
                          text: roleText,
                          color: s.iAmSeller ? AppColors.price : AppColors.brandDark,
                          background:
                              s.iAmSeller ? const Color(0xFFFFF0EC) : AppColors.brandSoft,
                        ),
                        const Spacer(),
                        Text(
                          Fmt.timeAgo(s.lastMsgAt),
                          style: const TextStyle(fontSize: 11, color: AppColors.inkFaint),
                        ),
                      ],
                    ),
                    const SizedBox(height: 4),
                    Text(
                      s.lastMsgText.isEmpty ? '（暂无消息）' : s.lastMsgText,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 13, color: AppColors.inkSoft),
                    ),
                    if (s.itemTitle.isNotEmpty) ...[
                      const SizedBox(height: 6),
                      Container(
                        padding: const EdgeInsets.all(6),
                        decoration: BoxDecoration(
                          color: AppColors.bg,
                          borderRadius: BorderRadius.circular(8),
                        ),
                        child: Row(
                          children: [
                            NetImage(url: s.itemCover, width: 30, height: 30, radius: 6),
                            const SizedBox(width: 8),
                            Expanded(
                              child: Text(
                                s.itemTitle,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(fontSize: 12),
                              ),
                            ),
                            if (s.itemStatusText.isNotEmpty)
                              Text(
                                s.itemStatusText,
                                style: const TextStyle(
                                  fontSize: 11,
                                  color: AppColors.inkFaint,
                                ),
                              ),
                          ],
                        ),
                      ),
                    ],
                    if (s.blockedByMe || s.blockedByPeer) ...[
                      const SizedBox(height: 6),
                      Text(
                        s.blockedByMe ? '你已拉黑对方' : '对方已拉黑你，无法继续发送',
                        style: const TextStyle(fontSize: 11, color: AppColors.price),
                      ),
                    ],
                  ],
                ),
              ),
              if (s.unread > 0) ...[
                const SizedBox(width: 8),
                Container(
                  margin: const EdgeInsets.only(top: 22),
                  padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                  constraints: const BoxConstraints(minWidth: 18),
                  decoration: BoxDecoration(
                    color: AppColors.price,
                    borderRadius: BorderRadius.circular(999),
                  ),
                  child: Text(
                    s.unread > 99 ? '99+' : s.unread.toString(),
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 10,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
              ],
              // 「⋯」菜单入口：长按能删但用户看不见，这个按钮把功能露出来（也是手机常见做法）。
              // 多选模式下藏起来：这时候整行是"勾选"，再放个单条删除入口会互相打架。
              if (!_selecting)
                GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onTap: () => _showSessionMenu(s),
                  child: Container(
                    width: 34,
                    height: 44,
                    alignment: Alignment.center,
                    color: Colors.transparent,
                    child: const Icon(Icons.more_horiz, size: 18, color: AppColors.inkFaint),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }

  /// 会话操作菜单（底部弹出，拇指可达）：单条删除 / 进入多选。
  Future<void> _showSessionMenu(ChatSession s) async {
    final action = await showModalBottomSheet<String>(
      context: context,
      backgroundColor: Colors.white,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(14)),
      ),
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 14, 16, 8),
              child: Text(
                s.peerName.isEmpty ? '对方' : s.peerName,
                style: const TextStyle(fontSize: 13, color: AppColors.inkSoft),
              ),
            ),
            const Divider(height: 1, color: AppColors.line),
            ListTile(
              leading: const Icon(Icons.checklist, color: AppColors.brandDark, size: 21),
              title: const Text('多选', style: TextStyle(fontSize: 14.5)),
              subtitle: const Text('批量删除多个会话',
                  style: TextStyle(fontSize: 11.5, color: AppColors.inkFaint)),
              onTap: () => Navigator.of(ctx).pop('multi'),
            ),
            ListTile(
              leading: const Icon(Icons.delete_outline, color: AppColors.price, size: 21),
              title: const Text('删除聊天', style: TextStyle(fontSize: 14.5)),
              subtitle: const Text('只删除你这一侧，对方不受影响',
                  style: TextStyle(fontSize: 11.5, color: AppColors.inkFaint)),
              onTap: () => Navigator.of(ctx).pop('delete'),
            ),
            ListTile(
              leading: const Icon(Icons.close, size: 21, color: AppColors.inkSoft),
              title: const Text('取消', style: TextStyle(fontSize: 14.5)),
              onTap: () => Navigator.of(ctx).pop(),
            ),
          ],
        ),
      ),
    );
    if (!mounted || action == null) return;
    if (action == 'delete') {
      await _confirmDelete(s);
    } else if (action == 'multi') {
      _enterSelection(s);
    }
  }
}
