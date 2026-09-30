import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'providers.dart';

/// 全局保活 + 生命周期钩子（挂在 home 外层，登录前后都在）：
///  - 让 chatSocketProvider / chatEventRouterProvider 常驻，长连接不会被回收；
///  - 回到前台（切后台久了连接会被系统掐掉）立刻重连并校准未读数。
class ChatConnectionKeeper extends ConsumerStatefulWidget {
  const ChatConnectionKeeper({super.key, required this.child});

  final Widget child;

  @override
  ConsumerState<ChatConnectionKeeper> createState() => _ChatConnectionKeeperState();
}

class _ChatConnectionKeeperState extends ConsumerState<ChatConnectionKeeper>
    with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state != AppLifecycleState.resumed) return;
    final ref = this.ref;
    ref.read(chatSocketProvider).ensureConnected();
    ref.read(unreadProvider.notifier).refresh();
    ref.read(chatRevisionProvider.notifier).bump();
  }

  @override
  Widget build(BuildContext context) {
    ref.watch(chatSocketProvider);
    ref.watch(chatEventRouterProvider);
    return widget.child;
  }
}
