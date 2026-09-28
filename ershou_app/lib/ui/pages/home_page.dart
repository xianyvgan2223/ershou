import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/providers.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/item_card.dart';
import 'favorites_page.dart';
import 'item_detail_page.dart';

/// 首页：搜索 + 分类 + 排序 + 双列瀑布流。
/// 顶栏和筛选区刻意压扁，把竖向空间都留给商品。
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
    'price_asc': '价格↑',
    'price_desc': '价格↓',
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

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(itemFeedProvider);
    final user = ref.watch(authProvider).user;
    return Scaffold(
      backgroundColor: AppColors.bg,
      body: SafeArea(
        bottom: false,
        child: Column(
          children: [
            _topBar(user?.nickname ?? '同学'),
            _filterRow(state),
            Expanded(child: _body(state)),
          ],
        ),
      ),
    );
  }

  /// 一行搞定：搜索框 + 排序 + 件数（原来占三行，现在只剩这一条）
  Widget _topBar(String nickname) {
    final state = ref.watch(itemFeedProvider);
    return Padding(
      padding: const EdgeInsets.fromLTRB(AppSpacing.page, 5, AppSpacing.page, 3),
      child: Row(
        children: [
          Expanded(
            child: SizedBox(
              height: 36,
              child: TextField(
                controller: _search,
                textInputAction: TextInputAction.search,
                style: const TextStyle(fontSize: 13.5),
                onSubmitted: (v) => ref.read(itemFeedProvider.notifier).search(v.trim()),
                onChanged: (_) => setState(() {}),
                decoration: InputDecoration(
                  isDense: true,
                  hintText: '搜「高数」「自行车」…',
                  hintStyle: const TextStyle(fontSize: 13, color: AppColors.inkFaint),
                  fillColor: Colors.white,
                  contentPadding: const EdgeInsets.symmetric(vertical: 8),
                  prefixIcon: const Icon(Icons.search, size: 17, color: AppColors.inkFaint),
                  prefixIconConstraints: const BoxConstraints(minWidth: 32, minHeight: 32),
                  suffixIcon: _search.text.isEmpty
                      ? null
                      : InkWell(
                          onTap: () {
                            _search.clear();
                            ref.read(itemFeedProvider.notifier).search('');
                            setState(() {});
                          },
                          child: const Icon(Icons.cancel,
                              size: 15, color: AppColors.inkFaint),
                        ),
                  suffixIconConstraints: const BoxConstraints(minWidth: 30, minHeight: 30),
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(999),
                    borderSide: BorderSide.none,
                  ),
                  enabledBorder: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(999),
                    borderSide: BorderSide.none,
                  ),
                  focusedBorder: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(999),
                    borderSide: const BorderSide(color: AppColors.brand, width: 1.2),
                  ),
                ),
              ),
            ),
          ),
          const SizedBox(width: 8),
          PopupMenuButton<String>(
            tooltip: '排序',
            onSelected: (v) => ref.read(itemFeedProvider.notifier).setSort(v),
            itemBuilder: (_) => [
              for (final entry in _sortLabels.entries)
                PopupMenuItem(value: entry.key, child: Text(entry.value)),
            ],
            child: Container(
              height: 36,
              padding: const EdgeInsets.symmetric(horizontal: 10),
              decoration: BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.circular(999),
              ),
              child: Row(
                children: [
                  Text(
                    _sortLabels[state.sort] ?? '最新',
                    style: const TextStyle(
                      fontSize: 13,
                      color: AppColors.brand,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  const Icon(Icons.expand_more, size: 16, color: AppColors.brand),
                ],
              ),
            ),
          ),
          const SizedBox(width: 6),
          // 收藏入口：放在顶栏右侧，比藏进「我的」里少点两次
          InkWell(
            borderRadius: BorderRadius.circular(999),
            onTap: () => Navigator.of(context).push(
              MaterialPageRoute<void>(builder: (_) => const FavoritesPage()),
            ),
            child: Container(
              height: 36,
              width: 36,
              alignment: Alignment.center,
              decoration: const BoxDecoration(
                color: Colors.white,
                shape: BoxShape.circle,
              ),
              child: const Icon(Icons.favorite_border,
                  size: 17, color: AppColors.price),
            ),
          ),
        ],
      ),
    );
  }

  /// 分类胶囊：高度 30，字号 12
  Widget _filterRow(ItemFeedState state) {
    final chips = <Widget>[
      _categoryChip(
        label: '全部',
        selected: state.categoryId == null,
        onTap: () => ref.read(itemFeedProvider.notifier).selectCategory(null),
      ),
    ];
    for (final c in state.categories) {
      chips.add(_categoryChip(
        label: c.name,
        selected: state.categoryId == c.id,
        onTap: () => ref.read(itemFeedProvider.notifier).selectCategory(c.id),
      ));
    }
    return SizedBox(
      height: 34,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.page),
        itemCount: chips.length,
        separatorBuilder: (_, __) => const SizedBox(width: 6),
        itemBuilder: (_, i) => chips[i],
      ),
    );
  }

  Widget _categoryChip({
    required String label,
    required bool selected,
    required VoidCallback onTap,
  }) {
    return Material(
      color: selected ? AppColors.brand : Colors.white,
      borderRadius: BorderRadius.circular(AppRadius.chip),
      child: InkWell(
        borderRadius: BorderRadius.circular(AppRadius.chip),
        onTap: onTap,
        child: Container(
          alignment: Alignment.center,
          padding: const EdgeInsets.symmetric(horizontal: 11),
          child: Text(
            label,
            style: TextStyle(
              fontSize: 12,
              fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
              color: selected ? Colors.white : AppColors.inkSoft,
            ),
          ),
        ),
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
            SizedBox(height: MediaQuery.of(context).size.height * 0.15),
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
            padding: const EdgeInsets.fromLTRB(AppSpacing.page, 7, AppSpacing.page, 0),
            sliver: SliverGrid(
              gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: 2,
                mainAxisSpacing: AppSpacing.gap,
                crossAxisSpacing: AppSpacing.gap,
                // 卡片高度 = 1:1 封面 + 两行标题 + 价格行 + 卖家行。
                // 0.60 是实测值：恰好容下两行标题，且不留大片空白。
                childAspectRatio: 0.60,
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
              padding: const EdgeInsets.fromLTRB(0, 12, 0, 16),
              child: Center(
                child: state.loadingMore
                    ? const SizedBox(
                        width: 18,
                        height: 18,
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
