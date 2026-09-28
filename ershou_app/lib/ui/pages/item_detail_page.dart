import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/network/api_client.dart';
import '../../core/providers.dart';
import '../../data/models.dart';
import '../widgets/net_image.dart';

class ItemDetailPage extends ConsumerStatefulWidget {
  const ItemDetailPage({super.key, required this.itemId});

  static const routeName = '/item';

  final int itemId;

  @override
  ConsumerState<ItemDetailPage> createState() => _ItemDetailPageState();
}

class _ItemDetailPageState extends ConsumerState<ItemDetailPage> {
  Item? _item;
  String? _error;
  bool _loading = true;
  int _page = 0;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final item = await ref.read(itemRepositoryProvider).detail(widget.itemId);
      if (!mounted) return;
      setState(() {
        _item = item;
        _loading = false;
      });
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.message;
        _loading = false;
      });
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
    final item = _item;
    return Scaffold(
      appBar: AppBar(title: Text(item?.title ?? '商品详情')),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : item == null
              ? Center(child: Text(_error ?? '商品不存在'))
              : RefreshIndicator(
                  onRefresh: _load,
                  child: ListView(
                    children: [
                      _gallery(item),
                      Padding(
                        padding: const EdgeInsets.all(16),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              '￥' + item.price.toStringAsFixed(2),
                              style: const TextStyle(fontSize: 24, fontWeight: FontWeight.bold),
                            ),
                            if (item.originalPrice != null)
                              Text(
                                '原价 ￥' + item.originalPrice!.toStringAsFixed(2),
                                style: const TextStyle(decoration: TextDecoration.lineThrough, color: Colors.grey),
                              ),
                            const SizedBox(height: 8),
                            Text(item.title, style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w600)),
                            const SizedBox(height: 8),
                            Wrap(
                              spacing: 8,
                              runSpacing: 4,
                              children: [
                                if (item.categoryName.isNotEmpty) Chip(label: Text(item.categoryName), visualDensity: VisualDensity.compact),
                                if (item.conditionText.isNotEmpty) Chip(label: Text(item.conditionText), visualDensity: VisualDensity.compact),
                                if (item.negotiable == 1) const Chip(label: Text('可议价'), visualDensity: VisualDensity.compact),
                                Chip(label: Text(item.statusText.isEmpty ? '状态未知' : item.statusText), visualDensity: VisualDensity.compact),
                              ],
                            ),
                            const SizedBox(height: 8),
                            Text(
                              '浏览 ' + item.viewCount.toString() + ' · 收藏 ' + item.favCount.toString() + ' · ' + item.publishedAt,
                              style: Theme.of(context).textTheme.bodySmall,
                            ),
                            const Divider(height: 32),
                            Text('卖家', style: Theme.of(context).textTheme.titleMedium),
                            const SizedBox(height: 8),
                            Row(
                              children: [
                                NetImage(
                                  url: item.seller?.avatar,
                                  width: 48,
                                  height: 48,
                                  radius: 24,
                                ),
                                const SizedBox(width: 12),
                                Expanded(
                                  child: Column(
                                    crossAxisAlignment: CrossAxisAlignment.start,
                                    children: [
                                      Text(item.seller?.nickname ?? '匿名用户', style: const TextStyle(fontWeight: FontWeight.w600)),
                                      Text(
                                        '信用分 ' + (item.seller?.creditScore ?? 0).toString() +
                                            (item.seller == null || item.seller!.campusName.isEmpty
                                                ? ''
                                                : ' · ' + item.seller!.campusName),
                                        style: Theme.of(context).textTheme.bodySmall,
                                      ),
                                    ],
                                  ),
                                ),
                              ],
                            ),
                            const Divider(height: 32),
                            Text('描述', style: Theme.of(context).textTheme.titleMedium),
                            const SizedBox(height: 8),
                            Text(item.description.isEmpty ? '卖家没有填写描述' : item.description),
                            if (item.preferPointName.isNotEmpty) ...[
                              const Divider(height: 32),
                              Text('建议面交点', style: Theme.of(context).textTheme.titleMedium),
                              const SizedBox(height: 8),
                              Text(item.preferPointName),
                            ],
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
    );
  }

  Widget _gallery(Item item) {
    final images = item.images.isEmpty
        ? (item.coverUrl.isEmpty ? <String>[] : <String>[item.coverUrl])
        : item.images;
    if (images.isEmpty) {
      return const AspectRatio(aspectRatio: 1, child: Center(child: Icon(Icons.image_not_supported_outlined)));
    }
    return Column(
      children: [
        AspectRatio(
          aspectRatio: 1,
          child: PageView.builder(
            itemCount: images.length,
            onPageChanged: (i) => setState(() => _page = i),
            itemBuilder: (_, i) => NetImage(url: images[i], radius: 0, fit: BoxFit.cover),
          ),
        ),
        if (images.length > 1)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Text((_page + 1).toString() + ' / ' + images.length.toString()),
          ),
      ],
    );
  }
}
