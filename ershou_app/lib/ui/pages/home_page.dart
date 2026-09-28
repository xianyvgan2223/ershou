import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/providers.dart';
import '../../data/models.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/item_card.dart';
import '../widgets/net_image.dart';
import 'connect_page.dart';
import 'item_detail_page.dart';
import 'login_page.dart';

class HomePage extends ConsumerStatefulWidget {
  const HomePage({super.key});

  @override
  ConsumerState<HomePage> createState() => _HomePageState();
}

class _HomePageState extends ConsumerState<HomePage> {
  final _scroll = ScrollController();
  final _search = TextEditingController();

  static const _sortLabels = <String, String>{
    'latest': '最新',
    'price_asc': '价格 ↑',
    'price_desc': '价格 ↓',
  };

  @override
  void initState() {
    super.initState();
    _scroll.addListener(() {
      final pos = _scroll.position;
      if (pos.pixels > pos.maxScrollExtent - 420) {
        ref.read(itemFeedProvider.notifier).loadMore();
      }
    });
    WidgetsBinding.instance.addPostFrameCallback((_) {
      ref.read(itemFeedProvider.notifier).refresh();
      ref.read(authProvider.notifier).refreshMe();
    });
  }

  @override
  void dispose() {
    _scroll.dispose();
    _search.dispose();
    super.dispose();
  }

  Future<void> _openAccountSheet() async {
    final user = ref.read(authProvider).user;
    final server = ref.read(activeServerProvider);
    await showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      builder: (sheetContext) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: Avatar(url: user?.avatar, name: user?.nickname, size: 44),
              title: Text(
                user?.nickname ?? '未加载',
                style: const TextStyle(fontWeight: FontWeight.w600),
              ),
              subtitle: Text(
                (user?.schoolName ?? '') +
                    (user?.campusName == null || user!.campusName.isEmpty
                        ? ''
                        : ' · ' + user.campusName) +
                    '  信用 ' +
                    (user?.creditScore ?? 0).toString(),
                style: const TextStyle(fontSize: 12),
              ),
            ),
            const Divider(height: 1),
            ListTile(
              leading: const Icon(Icons.dns_outlined),
              title: const Text('服务器地址'),
              subtitle: Text(server?.origin ?? '未配置'),
              trailing: const Icon(Icons.chevron_right),
              onTap: () {
                Navigator.of(sheetContext).pop();
                Navigator.of(context).push(
                  MaterialPageRoute<void>(builder: (_) => const ConnectPage()),
                );
              },
            ),
            ListTile(
              leading: const Icon(Icons.swap_horiz),
              title: const Text('切换账号'),
              onTap: () async {
                Navigator.of(sheetContext).pop();
                await ref.read(authProvider.notifier).logout();
                if (!mounted) return;
                Navigator.of(context).pushAndRemoveUntil(
                  MaterialPageRoute<void>(builder: (_) => const LoginPage()),
                  (route) => false,
                );
              },
            ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(itemFeedProvider);
    return Scaffold(
      backgroundColor: AppColors.bg,
      body: SafeArea(
        bottom: false,
        child: Column(
          children: [
            _header(),
            _searchBar(),
            _categoryBar(state),
            _sortBar(state),
            Expanded(child: _body(state)),
          ],
        ),
      ),
    );
  }

  Widget _header() {
    final user = ref.watch(authProvider).user;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 8, 8, 4),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '你好，' + (user?.nickname ?? '同学'),
                  style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
                ),
                const SizedBox(height: 2),
                Row(
                  children: [
                    const Icon(Icons.school_outlined, size: 13, color: AppColors.inkFaint),
                    const SizedBox(width: 4),
                    Text(
                      (user?.schoolName ?? '校园二手') +
                          (user == null || user.campusName.isEmpty ? '' : ' · ' + user.campusName),
                      style: const TextStyle(fontSize: 12, color: AppColors.inkFaint),
                    ),
                  ],
                ),
              ],
            ),
          ),
          IconButton(
            onPressed: _openAccountSheet,
            icon: Avatar(url: user?.avatar, name: user?.nickname, size: 36),
          ),
        ],
      ),
    );
  }

  Widget _searchBar() {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
      child: TextField(
        controller: _search,
        textInputAction: TextInputAction.search,
        onSubmitted: (v) => ref.read(itemFeedProvider.notifier).search(v.trim()),
        decoration: InputDecoration(
          hintText: '搜「高数」「自行车」「显示器」…',
          prefixIcon: const Icon(Icons.search, color: AppColors.inkFaint),
          suffixIcon: _search.text.isEmpty
              ? null
              : IconButton(
                  icon: const Icon(Icons.close, size: 18, color: AppColors.inkFaint),
                  onPressed: () {
                    _search.clear();
                    ref.read(itemFeedProvider.notifier).search('');
                    setState(() {});
                  },
                ),
          isDense: true,
          fillColor: Colors.white,
          border: OutlineInputBorder(
            borderRadius: BorderRadius.circular(999),
            borderSide: BorderSide.none,
          ),
          enabledBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(999),
            borderSide: BorderSide.none,
          ),
        ),
        onChanged: (_) => setState(() {}),
      ),
    );
  }

  Widget _categoryBar(ItemFeedState state) {
    final chips = <Widget>[
      _categoryChip(
        label: '全部',
        icon: Icons.apps_rounded,
        selected: state.categoryId == null,
        onTap: () => ref.read(itemFeedProvider.notifier).selectCategory(null),
      ),
    ];
    for (final c in state.categories) {
      chips.add(_categoryChip(
        label: c.name,
        icon: _iconFor(c.name),
        selected: state.categoryId == c.id,
        onTap: () => ref.read(itemFeedProvider.notifier).selectCategory(c.id),
      ));
    }
    return SizedBox(
      height: 40,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 16),
        itemCount: chips.length,
        separatorBuilder: (_, __) => const SizedBox(width: 8),
        itemBuilder: (_, i) => chips[i],
      ),
    );
  }

  Widget _categoryChip({
    required String label,
    required IconData icon,
    required bool selected,
    required VoidCallback onTap,
  }) {
    return Material(
      color: selected ? AppColors.brand : Colors.white,
      borderRadius: BorderRadius.circular(AppRadius.chip),
      child: InkWell(
        borderRadius: BorderRadius.circular(AppRadius.chip),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          child: Row(
            children: [
              Icon(icon, size: 15, color: selected ? Colors.white : AppColors.inkSoft),
              const SizedBox(width: 5),
              Text(
                label,
                style: TextStyle(
                  fontSize: 13,
                  fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
                  color: selected ? Colors.white : AppColors.ink,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  static IconData _iconFor(String name) {
    if (name.contains('书') || name.contains('教材')) return Icons.menu_book_outlined;
    if (name.contains('数码') || name.contains('电子')) return Icons.devices_other_outlined;
    if (name.contains('自行') || name.contains('出行')) return Icons.pedal_bike_outlined;
    if (name.contains('宿舍') || name.contains('生活')) return Icons.chair_outlined;
    if (name.contains('服饰') || name.contains('鞋')) return Icons.checkroom_outlined;
    if (name.contains('运动')) return Icons.sports_basketball_outlined;
    if (name.contains('美妆')) return Icons.brush_outlined;
    return Icons.sell_outlined;
  }

  Widget _sortBar(ItemFeedState state) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 10, 16, 6),
      child: Row(
        children: [
          for (final entry in _sortLabels.entries)
            Padding(
              padding: const EdgeInsets.only(right: 16),
              child: GestureDetector(
                onTap: () => ref.read(itemFeedProvider.notifier).setSort(entry.key),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      entry.value,
                      style: TextStyle(
                        fontSize: 13.5,
                        fontWeight: state.sort == entry.key ? FontWeight.w700 : FontWeight.w400,
                        color: state.sort == entry.key ? AppColors.brand : AppColors.inkSoft,
                      ),
                    ),
                    const SizedBox(height: 3),
                    Container(
                      width: 18,
                      height: 3,
                      decoration: BoxDecoration(
                        color: state.sort == entry.key ? AppColors.brand : Colors.transparent,
                        borderRadius: BorderRadius.circular(2),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          const Spacer(),
          Text(
            state.total.toString() + ' 件在售',
            style: const TextStyle(fontSize: 12, color: AppColors.inkFaint),
          ),
        ],
      ),
    );
  }

  Widget _body(ItemFeedState state) {
    if (state.loading && state.items.isEmpty) {
      return const SkeletonGrid();
    }
    if (state.items.isEmpty) {
      return RefreshIndicator(
        onRefresh: () => ref.read(itemFeedProvider.notifier).refresh(),
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          children: [
            SizedBox(height: MediaQuery.of(context).size.height * 0.18),
            EmptyState(
              icon: state.error == null ? Icons.inbox_outlined : Icons.wifi_off_rounded,
              message: state.error ?? '这里还没有商品，换个关键词试试',
              onRetry: () => ref.read(itemFeedProvider.notifier).refresh(),
            ),
          ],
        ),
      );
    }
    return RefreshIndicator(
      onRefresh: () => ref.read(itemFeedProvider.notifier).refresh(),
      child: CustomScrollView(
        controller: _scroll,
        physics: const AlwaysScrollableScrollPhysics(),
        slivers: [
          SliverPadding(
            padding: const EdgeInsets.fromLTRB(12, 6, 12, 0),
            sliver: SliverGrid(
              gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: 2,
                mainAxisSpacing: 12,
                crossAxisSpacing: 12,
                // 卡片内容（1:1 图 + 两行标题 + 价格行 + 卖家行）实测约 273px 高，
                // 0.58 能在 720x1600 这类窄屏上留出余量，避免底部溢出。
                childAspectRatio: 0.58,
              ),
              delegate: SliverChildBuilderDelegate(
                (context, index) {
                  final item = state.items[index];
                  return ItemCard(
                    item: item,
                    onTap: () => Navigator.of(context).push(
                      MaterialPageRoute<void>(
                        builder: (_) => ItemDetailPage(itemId: item.id),
                      ),
                    ),
                  );
                },
                childCount: state.items.length,
              ),
            ),
          ),
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 20),
              child: Center(
                child: state.loadingMore
                    ? const SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : Text(
                        state.hasMore ? '上拉加载更多' : '— 到底了 —',
                        style: const TextStyle(fontSize: 12, color: AppColors.inkFaint),
                      ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// 供其它页面复用：从任意位置跳到详情。
void openItem(BuildContext context, Item item) {
  Navigator.of(context).push(
    MaterialPageRoute<void>(builder: (_) => ItemDetailPage(itemId: item.id)),
  );
}
