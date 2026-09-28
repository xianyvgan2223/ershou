import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/providers.dart';
import '../theme/app_theme.dart';
import 'chat_list_page.dart';
import 'home_page.dart';
import 'map_page.dart';
import 'profile_page.dart';
import 'publish_page.dart';

/// 主壳：底部导航 + 中间发布按钮。
///
/// 4 个 tab 用 IndexedStack 保活（切回来不重新请求、列表位置也不丢）。
class ShellPage extends ConsumerStatefulWidget {
  const ShellPage({super.key});

  @override
  ConsumerState<ShellPage> createState() => _ShellPageState();
}

class _ShellPageState extends ConsumerState<ShellPage> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      ref.read(unreadProvider.notifier).refresh();
    });
  }

  Future<void> _openPublish() async {
    final published = await Navigator.of(context).push<bool>(
      MaterialPageRoute<bool>(builder: (_) => const PublishPage()),
    );
    if (published == true && mounted) {
      ref.read(itemFeedProvider.notifier).refresh();
    }
  }

  @override
  Widget build(BuildContext context) {
    final index = ref.watch(navIndexProvider);
    final unread = ref.watch(unreadProvider);
    // 关键：底栏放在 Column 里（而不是 Scaffold 的 bottomNavigationBar 槽位）。
    // 槽位方式下 Scaffold 会把 body 高度算成「整屏 - 底栏」，再叠加 SafeArea，
    // 于是 GridView 按比例算出来的卡片高度被截断，最后一行只剩一条边。
    return Scaffold(
      backgroundColor: AppColors.bg,
      body: Column(
        children: [
          Expanded(
            child: IndexedStack(
              index: index,
              children: const [
                HomePage(),
                ChatListPage(),
                MapPage(),
                ProfilePage(),
              ],
            ),
          ),
          _navBar(index, unread),
        ],
      ),
      floatingActionButton: SizedBox(
        width: 46,
        height: 46,
        child: FloatingActionButton(
          onPressed: _openPublish,
          backgroundColor: AppColors.brand,
          foregroundColor: Colors.white,
          elevation: 3,
          shape: const CircleBorder(),
          child: const Icon(Icons.add, size: 24),
        ),
      ),
      floatingActionButtonLocation: FloatingActionButtonLocation.centerDocked,
    );
  }

  Widget _navBar(int index, int unread) {
    return Container(
      decoration: const BoxDecoration(
        color: Colors.white,
        border: Border(top: BorderSide(color: AppColors.line)),
      ),
      child: SafeArea(
        top: false,
        child: SizedBox(
          height: AppSpacing.navBarHeight,
          child: Row(
            children: [
              _navItem(0, index, Icons.home_outlined, Icons.home_rounded, '首页'),
              _navItem(1, index, Icons.chat_bubble_outline, Icons.chat_bubble_rounded,
                  '消息', badge: unread),
              // 中间给悬浮发布按钮留位
              const SizedBox(width: 62),
              _navItem(2, index, Icons.map_outlined, Icons.map_rounded, '地图'),
              _navItem(3, index, Icons.person_outline, Icons.person_rounded, '我的'),
            ],
          ),
        ),
      ),
    );
  }

  Widget _navItem(
    int target,
    int current,
    IconData icon,
    IconData activeIcon,
    String label, {
    int badge = 0,
  }) {
    final selected = target == current;
    final color = selected ? AppColors.brand : AppColors.inkFaint;
    return Expanded(
      child: InkWell(
        onTap: () => ref.read(navIndexProvider.notifier).select(target),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Stack(
              clipBehavior: Clip.none,
              children: [
                Icon(selected ? activeIcon : icon, size: 18, color: color),
                if (badge > 0)
                  Positioned(
                    right: -8,
                    top: -4,
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 1),
                      constraints: const BoxConstraints(minWidth: 16),
                      decoration: BoxDecoration(
                        color: AppColors.price,
                        borderRadius: BorderRadius.circular(999),
                      ),
                      child: Text(
                        badge > 99 ? '99+' : badge.toString(),
                        textAlign: TextAlign.center,
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 9.5,
                          height: 1.5,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                  ),
              ],
            ),
            Text(
              label,
              style: TextStyle(
                fontSize: 9,
                color: color,
                fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
