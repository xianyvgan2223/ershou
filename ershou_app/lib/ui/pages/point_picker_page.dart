import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:webview_flutter/webview_flutter.dart';

import '../../core/providers.dart';
import '../../data/api_repository2.dart';
import '../../data/models2.dart';
import '../theme/app_theme.dart';

/// 地图选点：在真实高德地图上点一下，识别出附近地名，直接建成一个面交点。
///
/// 与 Web 端 `PointPickerDialog` 是同一套交互（地图选点 → 列出候选 → 手填/点选名称），
/// 但移动端走的是 WebView + 服务端 `/map/regeo`，不把高德 key 打进 App。
///
/// 成功后 `pop` 出新建的点位（`MapPoint`），由发布页接收并选中。
class PointPickerPage extends ConsumerStatefulWidget {
  const PointPickerPage({super.key, this.campusId});

  final int? campusId;

  @override
  ConsumerState<PointPickerPage> createState() => _PointPickerPageState();
}

class _PointPickerPageState extends ConsumerState<PointPickerPage> {
  WebViewController? _web;
  final _name = TextEditingController();
  final _guide = TextEditingController();
  double? _lng;
  double? _lat;
  int _pointType = 9;
  List<GeoCandidate> _candidates = const [];
  bool _locating = false;
  bool _saving = false;
  String? _error;
  /// 用户是否手动改过名称（改过就不再被自动识别覆盖）
  bool _nameTouched = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _initWeb());
  }

  @override
  void dispose() {
    _name.dispose();
    _guide.dispose();
    super.dispose();
  }

  void _initWeb() {
    final repo = ref.read(mapRepositoryProvider);
    final controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(const Color(0xFFEEF3F0))
      ..addJavaScriptChannel('FlutterBridge', onMessageReceived: _onBridge)
      ..loadRequest(Uri.parse(repo.amapPageUrl(
        campusId: widget.campusId ?? MapRepository.defaultCampusId,
        pickable: true,
        // 让页面点地图时顺手做逆地理编码（用页面里已有的 JS API，不需要 Web 服务 key）
        wantRegeo: true,
      )));
    setState(() => _web = controller);
  }

  void _onBridge(JavaScriptMessage message) {
    Map<String, dynamic> payload;
    try {
      payload = (jsonDecode(message.message) as Map).cast<String, dynamic>();
    } catch (_) {
      return;
    }
    if (payload['type'] != 'pick') return;
    final lng = (payload['lng'] as num?)?.toDouble();
    final lat = (payload['lat'] as num?)?.toDouble();
    if (lng == null || lat == null) return;
    // 候选地名由页面里的 AMap.Geocoder 一起回传（不用再打服务端）
    final raw = payload['candidates'];
    final candidates = raw is List
        ? raw
            .whereType<Map>()
            .map((e) => GeoCandidate.fromJson(e.cast<String, dynamic>()))
            .toList()
        : <GeoCandidate>[];
    _onPicked(lng, lat, candidates);
  }

  /// 点地图后：候选地名已经跟着回传，直接列出来让用户挑
  Future<void> _onPicked(double lng, double lat, List<GeoCandidate> candidates) async {
    setState(() {
      _lng = lng;
      _lat = lat;
      _candidates = candidates;
      _locating = false;
      _error = null;
      // 没手填过就用最近的一个候选自动填上，用户可随时改
      if ((!_nameTouched || _name.text.trim().isEmpty) && candidates.isNotEmpty) {
        _name.text = candidates.first.name;
        _nameTouched = false;
      }
    });
    // 页面没给出候选（反查失败）时，再试一次服务端兜底接口（配了 Web 服务 key 才有结果）
    if (candidates.isEmpty && mounted) {
      setState(() => _locating = true);
      try {
        final list = await ref.read(mapRepositoryProvider).regeoCandidates(lng: lng, lat: lat);
        if (mounted && list.isNotEmpty) {
          setState(() {
            _candidates = list;
            if (!_nameTouched || _name.text.trim().isEmpty) _name.text = list.first.name;
          });
        }
      } catch (_) {
        // 反查失败不影响选点，用户自己填名字即可
      } finally {
        if (mounted) setState(() => _locating = false);
      }
    }
  }

  Future<void> _save() async {
    final name = _name.text.trim();
    if (_lng == null || _lat == null) {
      setState(() => _error = '先在地图上点一下位置');
      return;
    }
    if (name.length < 2) {
      setState(() => _error = '名称至少 2 个字');
      return;
    }
    if (name.length > 30) {
      setState(() => _error = '名称最多 30 个字（当前 ${name.length}）');
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final point = await ref.read(mapRepositoryProvider).createPoint(
            name: name,
            lng: _lng!,
            lat: _lat!,
            pointType: _pointType,
            guideText: _guide.text.trim(),
            campusId: widget.campusId ?? MapRepository.defaultCampusId,
          );
      if (!mounted) return;
      Navigator.of(context).pop(point);
    } catch (e) {
      if (mounted) {
        setState(() {
          _saving = false;
          _error = e.toString();
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final picked = _lng != null && _lat != null;
    return Scaffold(
      backgroundColor: AppColors.bg,
      appBar: AppBar(
        title: const Text('在地图上选点'),
        actions: [
          TextButton(
            onPressed: _saving || !picked ? null : _save,
            child: _saving
                ? const SizedBox(
                    width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                : const Text('添加'),
          ),
        ],
      ),
      body: Column(
        children: [
          Expanded(
            child: Stack(
              children: [
                Positioned.fill(
                  child: _web == null
                      ? const Center(child: CircularProgressIndicator())
                      : WebViewWidget(controller: _web!),
                ),
                // 顶部提示：一句话说清怎么用
                Positioned(
                  left: 12,
                  right: 12,
                  top: 10,
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
                    decoration: BoxDecoration(
                      color: Colors.black.withValues(alpha: 0.62),
                      borderRadius: BorderRadius.circular(999),
                    ),
                    child: const Text(
                      '点击地图上你要面交的位置',
                      textAlign: TextAlign.center,
                      style: TextStyle(color: Colors.white, fontSize: 12.5),
                    ),
                  ),
                ),
              ],
            ),
          ),
          _sheet(picked),
        ],
      ),
    );
  }

  /// 底部表单：坐标 → 名称（+ 候选）→ 类型/指引
  Widget _sheet(bool picked) {
    return Container(
      color: Colors.white,
      padding: EdgeInsets.only(
        left: 16,
        right: 16,
        top: 12,
        bottom: MediaQuery.of(context).padding.bottom + 12,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (!picked)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 14),
              child: Text(
                '还没选位置。点上面的地图任意位置即可，能自动识别出附近的地名。',
                style: TextStyle(fontSize: 12.5, color: AppColors.inkSoft, height: 1.6),
              ),
            )
          else ...[
            Row(
              children: [
                const Icon(Icons.place, size: 15, color: AppColors.brandDark),
                const SizedBox(width: 4),
                Text(
                  '${_lng!.toStringAsFixed(6)}, ${_lat!.toStringAsFixed(6)}',
                  style: const TextStyle(fontSize: 11.5, color: AppColors.inkFaint),
                ),
                const Spacer(),
                if (_locating)
                  const SizedBox(
                      width: 12, height: 12, child: CircularProgressIndicator(strokeWidth: 2)),
              ],
            ),
            const SizedBox(height: 10),
            TextField(
              controller: _name,
              maxLength: 30,
              onChanged: (_) => _nameTouched = true,
              decoration: const InputDecoration(
                labelText: '地点名称',
                hintText: '自己填也行，例如：相思湖 / 清苑11栋',
                counterText: '',
                isDense: true,
              ),
            ),
            if (_candidates.isNotEmpty) ...[
              const SizedBox(height: 6),
              SizedBox(
                height: 34,
                child: ListView.separated(
                  scrollDirection: Axis.horizontal,
                  itemCount: _candidates.length,
                  separatorBuilder: (_, __) => const SizedBox(width: 6),
                  itemBuilder: (context, i) {
                    final c = _candidates[i];
                    final on = c.name == _name.text.trim();
                    return GestureDetector(
                      onTap: () => setState(() {
                        _name.text = c.name;
                        _nameTouched = true;
                      }),
                      child: Container(
                        padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 7),
                        decoration: BoxDecoration(
                          color: on ? AppColors.brandSoft : AppColors.bg,
                          borderRadius: BorderRadius.circular(AppRadius.chip),
                          border: Border.all(
                              color: on ? AppColors.brand : Colors.transparent),
                        ),
                        child: Text(
                          c.name,
                          style: TextStyle(
                            fontSize: 11.5,
                            color: on ? AppColors.brandDark : AppColors.inkSoft,
                          ),
                        ),
                      ),
                    );
                  },
                ),
              ),
            ],
            const SizedBox(height: 10),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _guide,
                    maxLength: 60,
                    decoration: const InputDecoration(
                      labelText: '指引说明（选填）',
                      hintText: '例如：从东门进直走 100 米',
                      counterText: '',
                      isDense: true,
                    ),
                  ),
                ),
                const SizedBox(width: 10),
                _typePicker(),
              ],
            ),
            if (_error != null) ...[
              const SizedBox(height: 8),
              Text(_error!,
                  style: const TextStyle(fontSize: 12, color: AppColors.price)),
            ],
            const SizedBox(height: 4),
            const Text(
              '新增的地点无需审核，买卖双方立刻可见',
              style: TextStyle(fontSize: 11, color: AppColors.inkFaint),
            ),
          ],
        ],
      ),
    );
  }

  Widget _typePicker() {
    const types = [9, 2, 4, 7, 5, 3, 1, 6];
    return PopupMenuButton<int>(
      initialValue: _pointType,
      onSelected: (v) => setState(() => _pointType = v),
      itemBuilder: (_) => [
        for (final t in types)
          PopupMenuItem(value: t, child: Text(MapPoint.typeLabel(t), style: const TextStyle(fontSize: 13))),
      ],
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 11),
        decoration: BoxDecoration(
          color: AppColors.bg,
          borderRadius: BorderRadius.circular(AppRadius.chip),
        ),
        child: Row(
          children: [
            Text(MapPoint.typeLabel(_pointType),
                style: const TextStyle(fontSize: 12.5, color: AppColors.inkSoft)),
            const Icon(Icons.expand_more, size: 16, color: AppColors.inkFaint),
          ],
        ),
      ),
    );
  }
}


