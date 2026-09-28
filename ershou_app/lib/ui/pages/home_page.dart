import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/providers.dart';
import '../../data/models.dart';
import '../widgets/net_image.dart';
import 'connect_page.dart';
import 'item_detail_page.dart';

class HomePage extends ConsumerStatefulWidget {
  const HomePage({super.key});

  @override
  ConsumerState<HomePage> createState() => _HomePageState();
}

class _HomePageState extends ConsumerState<HomePage> {
  final _scroll = ScrollController();
  final _search = TextEditingController();

  @override
  void initState() {
    super.initState();
    _scroll.addListener(() {
      if (_scroll.position.pixels > _scroll.position.maxScrollExtent - 400) {
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
    final server = ref.watch(activeServerProvider);
    return Scaffold(
      appBar: AppBar(
        title: const Text('校园二手'),
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(56),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 0, 12, 8),
            child: TextField(
              controller: _search,
              textInputAction: TextInputAction.search,
              onSubmitted: (v) => ref.read(itemFeedProvider.notifier).search(v.trim()),
              decoration: InputDecoration(
                hintText: '搜课程名 / 书名 / 物品（后端走 ES 中文分词）',
                prefixIcon: const Icon(Icons.search),
                isDense: true,
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(24)),
              ),
            ),
          ),
        ),
        actions: [
          PopupMenuButton<String>(
            icon: const Icon(Icons.more_vert),
            onSelected: (value) async {
              if (value == 'server') {
                Navigator.of(context).push(
                  MaterialPageRoute<void>(builder: (_) => const ConnectPage()),
                );
              } else if (value == 'logout') {
                await ref.read(authProvider.notifier).logout();
              } else if (value == 'me') {
                await ref.read(authProvider.notifier).refreshMe();
              }
            },
            itemBuilder: (_) => [
              PopupMenuItem(
                value: 'me',
                child: Text('当前账号：' +
                    (ref.read(authProvider).user?.nickname ?? '未加载')),
              ),
              PopupMenuItem(
                value: 'server',
                child: Text('服务器：' + (server?.origin ?? '-')),
              ),
              const PopupMenuItem(value: 'logout', child: Text('退出登录')),
            ],
          ),
        ],
      ),
      body: Column(
        children: [
          _categoryBar(state),
          _sortBar(state),
          Expanded(child: _list(state)),
        ],
      ),
    );
  }

  Widget _categoryBar(ItemFeedState state) {
    final chips = <Widget>[
      Padding(
        padding: const EdgeInsets.only(right: 8),
        child: ChoiceChip(
          label: const Text('全部'),
          selected: state.categoryId == null,
          onSelected: (_) => ref.read(itemFeedProvider.notifier).selectCategory(null),
        ),
      ),
    ];
    for (final c in state.categories) {
      chips.add(Padding(
        padding: const EdgeInsets.only(right: 8),
        child: ChoiceChip(
          label: Text(c.name),
          selected: state.categoryId == c.id,
          onSelected: (_) => ref.read(itemFeedProvider.notifier).selectCategory(c.id),
        ),
      ));
    }
    return SizedBox(
      height: 48,
      child: ListView(scrollDirection: Axis.horizontal, padding: const EdgeInsets.symmetric(horizontal: 12), children: chips),
    );
  }

  Widget _sortBar(ItemFeedState state) {
    const options = <String, String>{
      'latest': '最新',
      'price_asc': '价格从低到高',
      'price_desc': '价格从高到低',
    };
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      padding: const EdgeInsets.symmetric(horizontal: 12),
      child: Row(
        children: [
          for (final entry in options.entries)
            Padding(
              padding: const EdgeInsets.only(right: 8),
              child: FilterChip(
                label: Text(entry.value),
                selected: state.sort == entry.key,
                onSelected: (_) => ref.read(itemFeedProvider.notifier).setSort(entry.key),
              ),
            ),
          Text(
            '共 ' + state.total.toString() + ' 件',
            style: Theme.of(context).textTheme.bodySmall,
          ),
        ],
      ),
    );
  }

  Widget _list(ItemFeedState state) {
    if (state.loading && state.items.isEmpty) {
      return const Center(child: CircularProgressIndicator());
    }
    if (state.items.isEmpty) {
      return RefreshIndicator(
        onRefresh: () => ref.read(itemFeedProvider.notifier).refresh(),
        child: ListView(
          children: [
            const SizedBox(height: 120),
            Center(
              child: Text(state.error ?? '没有商品，试试换个关键词'),
            ),
          ],
        ),
      );
    }
    return RefreshIndicator(
      onRefresh: () => ref.read(itemFeedProvider.notifier).refresh(),
      child: GridView.builder(
        controller: _scroll,
        padding: const EdgeInsets.all(12),
        gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount: 2,
          mainAxisSpacing: 12,
          crossAxisSpacing: 12,
          childAspectRatio: 0.68,
        ),
        itemCount: state.items.length + 1,
        itemBuilder: (context, index) {
          if (index == state.items.length) {
            return Center(
              child: state.loadingMore
                  ? const CircularProgressIndicator()
                  : Text(state.hasMore ? '上拉加载更多' : '到底了'),
            );
          }
          return _card(state.items[index]);
        },
      ),
    );
  }

  Widget _card(Item item) {
    return InkWell(
      borderRadius: BorderRadius.circular(16),
      onTap: () => Navigator.of(context).push(
        MaterialPageRoute<void>(builder: (_) => ItemDetailPage(itemId: item.id)),
      ),
      child: Card(
        clipBehavior: Clip.antiAlias,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            AspectRatio(
              aspectRatio: 1,
              child: NetImage(url: item.coverUrl, radius: 0),
            ),
            Padding(
              padding: const EdgeInsets.all(8),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    item.title,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontWeight: FontWeight.w600),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    '￥' + item.price.toStringAsFixed(2),
                    style: TextStyle(
                      color: Theme.of(context).colorScheme.primary,
                      fontWeight: FontWeight.bold,
                      fontSize: 16,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    (item.seller?.nickname ?? '匿名') + ' · 浏览' + item.viewCount.toString(),
                    style: Theme.of(context).textTheme.bodySmall,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
