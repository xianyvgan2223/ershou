import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/providers.dart';
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

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => load());
  }

  Future<void> load() async {
    if (!mounted) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final list = await ref.read(chatRepositoryProvider).sessions();
      if (!mounted) return;
      setState(() {
        _sessions = list;
        _loading = false;
      });
      await ref.read(unreadProvider.notifier).refresh();
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.toString();
        _loading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.bg,
      appBar: AppBar(
        title: const Text('消息'),
        actions: [
          IconButton(
            onPressed: load,
            icon: const Icon(Icons.refresh, size: 20),
            tooltip: '刷新',
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _sessions.isEmpty
              ? EmptyState(
                  icon: Icons.forum_outlined,
                  message: _error ?? '还没有会话\n去商品详情页点「私聊」就能开始',
                  onRetry: load,
                )
              : RefreshIndicator(
                  onRefresh: load,
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
    );
  }

  Widget _tile(ChatSession s) {
    final roleText = s.iAmSeller ? '我卖' : '我买';
    return Material(
      color: Colors.white,
      child: InkWell(
        onTap: () async {
          await Navigator.of(context).push(
            MaterialPageRoute<void>(builder: (_) => ChatPage(session: s)),
          );
          if (mounted) load();
        },
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
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
            ],
          ),
        ),
      ),
    );
  }
}