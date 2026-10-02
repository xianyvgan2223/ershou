import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';

import '../../core/network/api_client.dart';
import '../../core/providers.dart';
import '../../data/models.dart';
import '../../data/models2.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/net_image.dart';
import 'point_picker_page.dart';

/// 发布闲置：多图上传 → 填信息 → 提交（后端会机审）。
///
/// 排版原则：能一行的不拆两行，校验错误就地显示，底部固定提交栏。
class PublishPage extends ConsumerStatefulWidget {
  const PublishPage({super.key, this.editing});

  /// 传入商品即为编辑模式（表单预填、提交走 PUT /items/{id}）
  final Item? editing;

  bool get isEditing => editing != null;

  @override
  ConsumerState<PublishPage> createState() => _PublishPageState();
}

class _PublishPageState extends ConsumerState<PublishPage> {
  static const int _maxImages = 6;
  static const _conditions = <int, String>{
    1: '全新未拆',
    2: '几乎全新',
    3: '轻微痕迹',
    4: '明显痕迹',
  };
  static const _quickPrices = <double>[5, 10, 20, 50, 100, 200];

  final _title = TextEditingController();
  final _desc = TextEditingController();
  final _price = TextEditingController();
  final _originalPrice = TextEditingController();
  final _stock = TextEditingController(text: '1');

  final _images = <String>[];
  List<Category> _categories = const [];
  List<MapPoint> _points = const [];
  int? _categoryId;
  int? _pointId;
  int _conditionLevel = 2;
  bool _negotiable = true;
  bool _unique = true;
  bool _submitting = false;
  bool _uploading = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _fillFromEditing();
    WidgetsBinding.instance.addPostFrameCallback((_) => _preload());
    _title.addListener(_onFieldChanged);
    _price.addListener(_onFieldChanged);
  }

  /// 编辑模式：把原商品的数据填进表单
  void _fillFromEditing() {
    final item = widget.editing;
    if (item == null) return;
    _title.text = item.title;
    _desc.text = item.description;
    _price.text = item.price == 0 ? '' : item.price.toString();
    _originalPrice.text =
        item.originalPrice == null ? '' : item.originalPrice!.toString();
    _stock.text = item.stock.toString();
    _conditionLevel = item.conditionLevel ?? 2;
    _negotiable = item.negotiable == 1;
    _unique = item.isUnique == 1;
    _images.addAll(item.images);
    _categoryId = item.categoryId;
    _pointId = item.preferPointId;
  }

  void _onFieldChanged() {
    // 用户开始改内容就把错误提示收掉，别一直挂着红字
    if (_error != null && mounted) setState(() => _error = null);
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    _title.dispose();
    _desc.dispose();
    _price.dispose();
    _originalPrice.dispose();
    _stock.dispose();
    super.dispose();
  }

  Future<void> _preload() async {
    try {
      final categories = await ref.read(itemRepositoryProvider).categories();
      final points = await ref.read(mapRepositoryProvider).tradePoints();
      if (!mounted) return;
      setState(() {
        _categories = categories;
        _points = points;
        _categoryId = categories.isEmpty ? null : categories.first.id;
      });
    } catch (_) {
      // 下拉数据失败不阻塞填表
    }
  }

  Future<void> _pickImages() async {
    if (_images.length >= _maxImages) {
      showToast(context, '最多 ' + _maxImages.toString() + ' 张图');
      return;
    }
    final picked = await ImagePicker().pickMultiImage(
      imageQuality: 82,
      maxWidth: 1400,
      limit: _maxImages - _images.length,
    );
    if (picked.isEmpty) return;
    setState(() => _uploading = true);
    try {
      for (final file in picked) {
        final url = await ref.read(itemWriteRepositoryProvider).upload(file.path);
        if (url.isEmpty) continue;
        if (!mounted) return;
        setState(() => _images.add(url));
      }
    } on ApiException catch (e) {
      if (mounted) showToast(context, '上传失败：' + e.message);
    } catch (e) {
      if (mounted) showToast(context, '上传失败：' + e.toString());
    } finally {
      if (mounted) setState(() => _uploading = false);
    }
  }

  /// 图片列表是否与原商品一致（顺序也要一致，第一张是封面）
  bool _sameImages(List<String> old) {
    if (old.length != _images.length) return false;
    for (var i = 0; i < old.length; i++) {
      if (old[i] != _images[i]) return false;
    }
    return true;
  }

  String? _validate() {
    final title = _title.text.trim();
    if (title.isEmpty) return '先写个标题，买家第一眼看的就是它';
    if (title.length < 4) return '标题太短了，写清楚是什么东西';
    final price = double.tryParse(_price.text.trim());
    if (price == null || price <= 0) return '售价要填一个大于 0 的数字';
    final original = double.tryParse(_originalPrice.text.trim());
    if (original != null && original > 0 && original < price) {
      return '原价比售价还低，是不是填反了';
    }
    if (_categoryId == null) return '分类还没加载好，下拉刷新一下';
    if (_images.isEmpty) return '至少传 1 张图，二手没图基本没人点进来';
    return null;
  }

  Future<void> _submit() async {
    final error = _validate();
    if (error != null) {
      setState(() => _error = error);
      return;
    }
    setState(() {
      _error = null;
      _submitting = true;
    });
    try {
      final repo = ref.read(itemWriteRepositoryProvider);
      final title = _title.text.trim();
      final description = _desc.text.trim();
      final price = double.parse(_price.text.trim());
      final originalPrice = double.tryParse(_originalPrice.text.trim());
      final stock = _unique ? 1 : (int.tryParse(_stock.text.trim()) ?? 1);
      final negotiable = _negotiable ? 1 : 0;
      final isUnique = _unique ? 1 : 0;

      final Map<String, dynamic> result;
      if (widget.isEditing) {
        // 编辑：只把跟原值不同的字段发出去，避免误覆盖
        final old = widget.editing!;
        result = await repo.update(
          old.id,
          title: title == old.title ? null : title,
          description: description == old.description ? null : description,
          categoryId: _categoryId == old.categoryId ? null : _categoryId,
          conditionLevel:
              _conditionLevel == old.conditionLevel ? null : _conditionLevel,
          price: price == old.price ? null : price,
          originalPrice:
              originalPrice == old.originalPrice ? null : originalPrice,
          negotiable: negotiable == old.negotiable ? null : negotiable,
          stock: stock == old.stock ? null : stock,
          isUnique: isUnique == old.isUnique ? null : isUnique,
          images: _sameImages(old.images) ? null : _images,
          preferPointId:
              _pointId == old.preferPointId ? null : _pointId,
        );
      } else {
        result = await repo.publish(
          title: title,
          description: description,
          categoryId: _categoryId!,
          price: price,
          originalPrice: originalPrice,
          conditionLevel: _conditionLevel,
          negotiable: negotiable,
          stock: stock,
          isUnique: isUnique,
          images: _images,
          preferPointId: _pointId,
        );
      }
      if (!mounted) return;
      final statusText = (result['statusText'] ?? '').toString();
      final needReview = statusText.contains('审核');
      showToast(
        context,
        widget.isEditing
            ? (needReview ? '已保存，改动需人工复审' : '已保存')
            : (needReview ? '已提交，命中可疑词转人工审核' : '发布成功：' + statusText),
      );
      Navigator.of(context).pop(true);
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _submitting = false;
        // 50001 = 命中禁售词；直接把后端文案展示出来
        _error = e.code == 50001 ? '内容被拦截：' + e.message : e.message;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _submitting = false;
        _error = e.toString();
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: !_submitting,
      child: Scaffold(
        backgroundColor: AppColors.bg,
        appBar: AppBar(
          title: Text(widget.isEditing ? '编辑商品' : '发布闲置'),
          actions: [
            if (_title.text.isNotEmpty || _images.isNotEmpty)
              TextButton(
                onPressed: () => _confirmDiscard(),
                child: const Text('清空', style: TextStyle(fontSize: 13)),
              ),
          ],
        ),
        body: ListView(
          padding: const EdgeInsets.fromLTRB(
            AppSpacing.page,
            AppSpacing.page,
            AppSpacing.page,
            16,
          ),
          children: [
            if (!widget.isEditing) ...[
              _validityNotice(),
              const SizedBox(height: AppSpacing.gap),
            ],
            _imageCard(),
            const SizedBox(height: AppSpacing.gap),
            _infoCard(),
            const SizedBox(height: AppSpacing.gap),
            _priceCard(),
            const SizedBox(height: AppSpacing.gap),
            _categoryCard(),
            const SizedBox(height: AppSpacing.gap),
            _pointCard(),
          ],
        ),
        bottomNavigationBar: _submitBar(),
      ),
    );
  }

  Future<void> _confirmDiscard() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('清空重填？'),
        content: const Text('已填的内容和已上传的图片都会丢掉。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('继续编辑'),
          ),
          TextButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('清空', style: TextStyle(color: AppColors.price)),
          ),
        ],
      ),
    );
    if (ok != true) return;
    setState(() {
      _images.clear();
      _title.clear();
      _desc.clear();
      _price.clear();
      _originalPrice.clear();
      _error = null;
    });
  }

  /// 有效期提示：这条规则卖家最该先知道，所以放在表单最前面（与网页端一致）
  Widget _validityNotice() {
    return Container(
      padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
      decoration: BoxDecoration(
        color: const Color(0xFFFFF7ED),
        border: Border.all(color: const Color(0xFFFDE3C8)),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Icon(Icons.schedule, size: 16, color: AppColors.price),
          const SizedBox(width: 8),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: const [
                Text(
                  '每件商品可挂 7 天',
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                    color: Color(0xFF9A3412),
                  ),
                ),
                SizedBox(height: 3),
                Text(
                  '到期还没卖出会自动下架（不扣信用分）。在「我的发布」里点一下重新上架就能再挂 7 天，图片和描述都不用重填。',
                  style: TextStyle(
                    fontSize: 11.5,
                    height: 1.6,
                    color: Color(0xFFB45309),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  /// 图片：4 列方形格子，右上角删除，第一张标封面
  Widget _imageCard() {    return AppCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SectionHeader(
            title: '商品图片',
            subtitle: _images.length.toString() +
                '/' +
                _maxImages.toString() +
                ' · 第一张是封面',
          ),
          GridView.count(
            shrinkWrap: true,
            physics: const NeverScrollableScrollPhysics(),
            crossAxisCount: 4,
            mainAxisSpacing: 8,
            crossAxisSpacing: 8,
            children: [
              for (var i = 0; i < _images.length; i++) _imageCell(i),
              if (_images.length < _maxImages) _addImageCell(),
            ],
          ),
          if (_images.isEmpty) ...[
            const SizedBox(height: 8),
            const Text(
              '建议：封面拍整体，第二张拍瑕疵，再拍包装或配件。',
              style: TextStyle(fontSize: 11.5, color: AppColors.inkFaint),
            ),
          ],
        ],
      ),
    );
  }

  Widget _imageCell(int index) {
    return Stack(
      clipBehavior: Clip.none,
      children: [
        Positioned.fill(
          child: NetImage(url: _images[index], radius: 10),
        ),
        if (index == 0)
          Positioned(
            left: 4,
            bottom: 4,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 1),
              decoration: BoxDecoration(
                color: Colors.black54,
                borderRadius: BorderRadius.circular(4),
              ),
              child: const Text(
                '封面',
                style: TextStyle(color: Colors.white, fontSize: 9),
              ),
            ),
          ),
        Positioned(
          right: -5,
          top: -5,
          child: GestureDetector(
            onTap: () => setState(() => _images.removeAt(index)),
            child: Container(
              padding: const EdgeInsets.all(3),
              decoration: const BoxDecoration(
                color: Color(0xCC000000),
                shape: BoxShape.circle,
              ),
              child: const Icon(Icons.close, size: 11, color: Colors.white),
            ),
          ),
        ),
      ],
    );
  }

  Widget _addImageCell() {
    return GestureDetector(
      onTap: _uploading ? null : _pickImages,
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: AppColors.bg,
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: AppColors.line),
        ),
        child: Center(
          child: _uploading
              ? const SizedBox(
                  width: 16,
                  height: 16,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : const Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Icon(Icons.add_a_photo_outlined,
                        color: AppColors.inkFaint, size: 19),
                    SizedBox(height: 2),
                    Text(
                      '加图',
                      style: TextStyle(fontSize: 10, color: AppColors.inkFaint),
                    ),
                  ],
                ),
        ),
      ),
    );
  }

  /// 标题 + 描述 + 成色，合成一张卡减少视觉分割
  Widget _infoCard() {
    return AppCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          TextField(
            controller: _title,
            maxLength: 40,
            textInputAction: TextInputAction.next,
            decoration: const InputDecoration(
              labelText: '标题',
              hintText: '高等数学 同济第七版 九成新',
            ),
          ),
          const SizedBox(height: 4),
          TextField(
            controller: _desc,
            maxLines: 3,
            maxLength: 500,
            decoration: const InputDecoration(
              labelText: '描述（选填）',
              hintText: '买来只用过一学期，有几页铅笔笔记，可以擦掉',
              alignLabelWithHint: true,
            ),
          ),
          const SizedBox(height: 4),
          const Padding(
            padding: EdgeInsets.only(left: 2, bottom: 6),
            child: Text(
              '成色',
              style: TextStyle(fontSize: 12, color: AppColors.inkFaint),
            ),
          ),
          Wrap(
            spacing: 6,
            runSpacing: 6,
            children: [
              for (final entry in _conditions.entries)
                GestureDetector(
                  onTap: () => setState(() => _conditionLevel = entry.key),
                  child: AnimatedContainer(
                    duration: const Duration(milliseconds: 140),
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                    decoration: BoxDecoration(
                      color: _conditionLevel == entry.key
                          ? AppColors.brandSoft
                          : AppColors.bg,
                      borderRadius: BorderRadius.circular(AppRadius.chip),
                      border: Border.all(
                        color: _conditionLevel == entry.key
                            ? AppColors.brand
                            : Colors.transparent,
                      ),
                    ),
                    child: Text(
                      entry.value,
                      style: TextStyle(
                        fontSize: 12.5,
                        color: _conditionLevel == entry.key
                            ? AppColors.brandDark
                            : AppColors.inkSoft,
                        fontWeight: _conditionLevel == entry.key
                            ? FontWeight.w600
                            : FontWeight.w400,
                      ),
                    ),
                  ),
                ),
            ],
          ),
        ],
      ),
    );
  }

  /// 价格：售价 + 原价一行，快捷价格一行，议价开关
  Widget _priceCard() {
    final price = double.tryParse(_price.text.trim());
    return AppCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _price,
                  keyboardType: const TextInputType.numberWithOptions(decimal: true),
                  inputFormatters: [
                    FilteringTextInputFormatter.allow(RegExp('[0-9.]')),
                  ],
                  style: const TextStyle(
                    fontSize: 18,
                    fontWeight: FontWeight.w600,
                    color: AppColors.price,
                  ),
                  decoration: const InputDecoration(
                    labelText: '售价',
                    prefixText: '￥ ',
                  ),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: TextField(
                  controller: _originalPrice,
                  keyboardType: const TextInputType.numberWithOptions(decimal: true),
                  inputFormatters: [
                    FilteringTextInputFormatter.allow(RegExp('[0-9.]')),
                  ],
                  decoration: const InputDecoration(
                    labelText: '原价（选填）',
                    prefixText: '￥ ',
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          Wrap(
            spacing: 6,
            runSpacing: 6,
            children: [
              for (final p in _quickPrices)
                GestureDetector(
                  onTap: () => setState(() => _price.text = p.toStringAsFixed(0)),
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                    decoration: BoxDecoration(
                      color: price == p ? AppColors.brandSoft : AppColors.bg,
                      borderRadius: BorderRadius.circular(AppRadius.chip),
                    ),
                    child: Text(
                      '￥' + p.toStringAsFixed(0),
                      style: TextStyle(
                        fontSize: 12,
                        color: price == p ? AppColors.brandDark : AppColors.inkSoft,
                      ),
                    ),
                  ),
                ),
            ],
          ),
          const SizedBox(height: 4),
          _switchRow(
            title: '接受议价',
            subtitle: '买家可以还价，成交更快',
            value: _negotiable,
            onChanged: (v) => setState(() => _negotiable = v),
          ),
          _switchRow(
            title: '孤品（只有一件）',
            subtitle: _unique ? '卖出后自动下架' : '按库存数量卖',
            value: _unique,
            onChanged: (v) => setState(() => _unique = v),
          ),
          if (!_unique) ...[
            const SizedBox(height: 6),
            TextField(
              controller: _stock,
              keyboardType: TextInputType.number,
              inputFormatters: [FilteringTextInputFormatter.digitsOnly],
              decoration: const InputDecoration(labelText: '库存数量'),
            ),
          ],
        ],
      ),
    );
  }

  Widget _switchRow({
    required String title,
    required String subtitle,
    required bool value,
    required ValueChanged<bool> onChanged,
  }) {
    return InkWell(
      onTap: () => onChanged(!value),
      borderRadius: BorderRadius.circular(10),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: const TextStyle(fontSize: 14)),
                  Text(
                    subtitle,
                    style: const TextStyle(fontSize: 11.5, color: AppColors.inkFaint),
                  ),
                ],
              ),
            ),
            Switch(
              value: value,
              activeThumbColor: AppColors.brand,
              onChanged: onChanged,
            ),
          ],
        ),
      ),
    );
  }

  Widget _categoryCard() {
    return AppCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const SectionHeader(title: '分类'),
          Wrap(
            spacing: 6,
            runSpacing: 6,
            children: [
              for (final c in _categories)
                GestureDetector(
                  onTap: () => setState(() => _categoryId = c.id),
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                    decoration: BoxDecoration(
                      color: _categoryId == c.id ? AppColors.brand : AppColors.bg,
                      borderRadius: BorderRadius.circular(AppRadius.chip),
                    ),
                    child: Text(
                      c.name,
                      style: TextStyle(
                        fontSize: 12.5,
                        color: _categoryId == c.id ? Colors.white : AppColors.inkSoft,
                        fontWeight: _categoryId == c.id
                            ? FontWeight.w600
                            : FontWeight.w400,
                      ),
                    ),
                  ),
                ),
            ],
          ),
          if (_categories.isEmpty)
            const Padding(
              padding: EdgeInsets.only(top: 8),
              child: Text(
                '分类还没加载出来，检查一下服务器连接',
                style: TextStyle(fontSize: 11.5, color: AppColors.price),
              ),
            ),
        ],
      ),
    );
  }

  Widget _pointCard() {
    final selected = _points.where((p) => p.id == _pointId).toList();
    final current = selected.isEmpty ? null : selected.first;
    return AppCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Expanded(
                child: SectionHeader(title: '面交点', subtitle: '选填 · 校内面交更安全'),
              ),
              // 选点入口：地图上自己点一个（免审核、立刻可用）
              TextButton.icon(
                onPressed: _openPointPicker,
                icon: const Icon(Icons.add_location_alt_outlined, size: 15),
                label: const Text('地图上选', style: TextStyle(fontSize: 12.5)),
                style: TextButton.styleFrom(
                  padding: const EdgeInsets.symmetric(horizontal: 8),
                  minimumSize: const Size(0, 30),
                  tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                ),
              ),
            ],
          ),
          const SizedBox(height: 2),
          // 已选：一行说清楚（名称 + 清除），比一堆 chip 干净
          if (current != null) ...[
            Row(
              children: [
                const Icon(Icons.place, size: 15, color: AppColors.brandDark),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(
                    current.name,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                        fontSize: 13, fontWeight: FontWeight.w600, color: AppColors.brandDark),
                  ),
                ),
                GestureDetector(
                  onTap: () => setState(() => _pointId = null),
                  child: const Padding(
                    padding: EdgeInsets.all(2),
                    child: Icon(Icons.close, size: 15, color: AppColors.inkFaint),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
          ],
          if (_points.isEmpty)
            const Text(
              '还没加载到点位，可以点「地图上选」自己加一个，或先不选',
              style: TextStyle(fontSize: 12, color: AppColors.inkFaint, height: 1.5),
            )
          else
            // 常用点位：只给最近的几个，其余走"地图上选"，避免一大片 chip 把页面撑长
            Wrap(
              spacing: 6,
              runSpacing: 6,
              children: [
                for (final p in _points.take(5))
                  GestureDetector(
                    onTap: () => setState(
                      () => _pointId = _pointId == p.id ? null : p.id,
                    ),
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 6),
                      decoration: BoxDecoration(
                        color: _pointId == p.id ? AppColors.brandSoft : AppColors.bg,
                        borderRadius: BorderRadius.circular(AppRadius.chip),
                        border: Border.all(
                          color: _pointId == p.id ? AppColors.brand : Colors.transparent,
                        ),
                      ),
                      child: Text(
                        p.name,
                        style: TextStyle(
                          fontSize: 12,
                          color: _pointId == p.id ? AppColors.brandDark : AppColors.inkSoft,
                        ),
                      ),
                    ),
                  ),
              ],
            ),
        ],
      ),
    );
  }

  /// 打开地图选点：在真实高德地图上点一下 → 服务端反查地名 → 建成点位 → 自动选中
  Future<void> _openPointPicker() async {
    final created = await Navigator.of(context).push<MapPoint>(
      MaterialPageRoute<MapPoint>(builder: (_) => const PointPickerPage()),
    );
    if (created == null || !mounted) return;
    setState(() {
      // 新点位插到列表最前，并直接选中——用户刚建完就是想用它
      if (_points.every((p) => p.id != created.id)) {
        _points = [created, ..._points];
      }
      _pointId = created.id;
    });
    showToast(context, '已新增「${created.name}」并选中');
  }

  /// 底部固定提交栏：错误就近显示，按钮常驻可见
  Widget _submitBar() {
    return Container(
      padding: EdgeInsets.only(
        left: AppSpacing.page,
        right: AppSpacing.page,
        top: 10,
        bottom: MediaQuery.of(context).padding.bottom + 10,
      ),
      decoration: const BoxDecoration(
        color: Colors.white,
        border: Border(top: BorderSide(color: AppColors.line)),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (_error != null) ...[
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Icon(Icons.error_outline, size: 15, color: AppColors.price),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(
                    _error!,
                    style: const TextStyle(
                      fontSize: 12.5,
                      color: AppColors.price,
                      height: 1.4,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
          ],
          SizedBox(
            width: double.infinity,
            child: FilledButton(
              onPressed: (_submitting || _uploading) ? null : _submit,
              child: _submitting
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: Colors.white,
                      ),
                    )
                  : Text(_uploading
                      ? '图片上传中…'
                      : (widget.isEditing ? '保存修改' : '发布')),
            ),
          ),
        ],
      ),
    );
  }
}
