import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/providers.dart';
import '../../core/utils/formatters.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/net_image.dart';
import 'connect_page.dart';
import 'favorites_page.dart';
import 'meetup_orders_page.dart';
import 'my_items_page.dart';

class ProfilePage extends ConsumerStatefulWidget {
  const ProfilePage({super.key});

  @override
  ConsumerState<ProfilePage> createState() => _ProfilePageState();
}

class _ProfilePageState extends ConsumerState<ProfilePage> {
  int _todoCount = 0;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _refresh());
  }

  Future<void> _refresh() async {
    await ref.read(authProvider.notifier).refreshMe();
    try {
      final n = await ref.read(orderRepositoryProvider).todoCount();
      if (mounted) setState(() => _todoCount = n);
    } catch (_) {
      // 红点拿不到不影响页面
    }
  }

  @override
  Widget build(BuildContext context) {
    final user = ref.watch(authProvider).user;
    return Scaffold(
      backgroundColor: AppColors.bg,
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: ListView(
          padding: const EdgeInsets.only(bottom: 24),
          children: [
            _header(context, user),
            const SizedBox(height: 12),
            _stats(user),
            const SizedBox(height: 12),
            _menuGroup([
              _menuTile(Icons.favorite_border, '我的收藏',
                  onTap: () => _push(const FavoritesPage())),
              _menuTile(Icons.sell_outlined, '我的发布',
                  onTap: () => _push(const MyItemsPage())),
              _menuTile(Icons.handshake_outlined, '我的面交',
                  badge: _todoCount, onTap: () => _push(const MeetupOrdersPage())),
            ]),
            const SizedBox(height: 12),
            _menuGroup([
              _menuTile(Icons.dns_outlined, '服务器地址',
                  subtitle: ref.watch(activeServerProvider)?.origin ?? '未配置',
                  onTap: () => _push(const ConnectPage())),
              _menuTile(Icons.swap_horiz, '切换账号', onTap: _logout),
              _menuTile(Icons.logout, '退出登录', onTap: _logout),
            ]),
            const SizedBox(height: 24),
            Center(
              child: Text(
                '校园二手 · 手机端 v1.0',
                style: const TextStyle(fontSize: 11, color: AppColors.inkFaint),
              ),
            ),
          ],
        ),
      ),
    );
  }

  void _push(Widget page) {
    Navigator.of(context)
        .push(MaterialPageRoute<void>(builder: (_) => page))
        .then((_) => _refresh());
  }

  Future<void> _logout() async {
    await ref.read(authProvider.notifier).logout();
    ref.read(unreadProvider.notifier).clear();
  }

  Widget _header(BuildContext context, dynamic user) {
    return Container(
      width: double.infinity,
      padding: EdgeInsets.only(
        top: MediaQuery.of(context).padding.top + 14,
        left: 16,
        right: 16,
        bottom: 18,
      ),
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          colors: [AppColors.brand, Color(0xFF00D08A)],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        borderRadius: BorderRadius.vertical(bottom: Radius.circular(24)),
      ),
      child: Row(
        children: [
          Container(
            padding: const EdgeInsets.all(2),
            decoration: const BoxDecoration(color: Colors.white, shape: BoxShape.circle),
            child: Avatar(url: user?.avatar, name: user?.nickname, size: 52),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  user?.nickname ?? '未登录',
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 19,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  Fmt.phone(user?.phone),
                  style: const TextStyle(color: Colors.white70, fontSize: 12),
                ),
                const SizedBox(height: 4),
                Text(
                  (user?.schoolName ?? '') +
                      (user == null || user.campusName.isEmpty
                          ? ''
                          : ' · ' + user.campusName),
                  style: const TextStyle(color: Colors.white70, fontSize: 12),
                ),
              ],
            ),
          ),
          if (user?.isAdmin == true)
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
              decoration: BoxDecoration(
                color: Colors.white24,
                borderRadius: BorderRadius.circular(999),
              ),
              child: const Text(
                '运营',
                style: TextStyle(color: Colors.white, fontSize: 11),
              ),
            ),
        ],
      ),
    );
  }

  Widget _stats(dynamic user) {
    Widget cell(String label, String value, {Color? color}) => Expanded(
          child: Column(
            children: [
              Text(
                value,
                style: TextStyle(
                  fontSize: 17,
                  fontWeight: FontWeight.w700,
                  color: color ?? AppColors.ink,
                ),
              ),
              const SizedBox(height: 2),
              Text(label, style: const TextStyle(fontSize: 11.5, color: AppColors.inkFaint)),
            ],
          ),
        );
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.page),
      child: AppCard(
        child: Row(
          children: [
            cell('信用分', (user?.creditScore ?? 0).toString(), color: AppColors.brand),
            Container(width: 1, height: 28, color: AppColors.line),
            cell('发布额度', (user?.publishLimit ?? 0).toString()),
            Container(width: 1, height: 28, color: AppColors.line),
            cell('待办面交', _todoCount.toString(),
                color: _todoCount > 0 ? AppColors.price : AppColors.ink),
          ],
        ),
      ),
    );
  }

  Widget _menuGroup(List<Widget> children) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.page),
      child: AppCard(
        padding: EdgeInsets.zero,
        child: Column(children: children),
      ),
    );
  }

  Widget _menuTile(
    IconData icon,
    String title, {
    String? subtitle,
    int badge = 0,
    VoidCallback? onTap,
  }) {
    return ListTile(
      leading: Container(
        padding: const EdgeInsets.all(8),
        decoration: BoxDecoration(
          color: AppColors.brandSoft,
          borderRadius: BorderRadius.circular(10),
        ),
        child: Icon(icon, size: 18, color: AppColors.brandDark),
      ),
      title: Text(title, style: const TextStyle(fontSize: 14.5)),
      subtitle: subtitle == null
          ? null
          : Text(subtitle, style: const TextStyle(fontSize: 11.5)),
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (badge > 0)
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
              decoration: BoxDecoration(
                color: AppColors.price,
                borderRadius: BorderRadius.circular(999),
              ),
              child: Text(
                badge.toString(),
                style: const TextStyle(color: Colors.white, fontSize: 10),
              ),
            ),
          const Icon(Icons.chevron_right, size: 18, color: AppColors.inkFaint),
        ],
      ),
      onTap: onTap,
    );
  }
}
