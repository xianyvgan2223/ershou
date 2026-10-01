import 'package:flutter/material.dart';

/// 全站配色 / 圆角 / 阴影的唯一来源，页面里不要再写魔法数字。
class AppColors {
  const AppColors._();

  static const brand = Color(0xFF00B578);
  static const brandDark = Color(0xFF00915F);
  static const brandSoft = Color(0xFFE6F8F1);
  static const price = Color(0xFFFF4D2D);
  static const bg = Color(0xFFF5F6F8);
  static const ink = Color(0xFF1A1D1F);
  static const inkSoft = Color(0xFF6B7280);
  static const inkFaint = Color(0xFF9CA3AF);
  static const line = Color(0xFFEDEFF2);
}

class AppRadius {
  const AppRadius._();

  static const card = 13.0;
  static const chip = 999.0;
  static const field = 11.0;
}

/// 页面留白：统一收窄，避免「到处是边框」的感觉。
class AppSpacing {
  const AppSpacing._();

  /// 卡片内边距
  static const cardPadding = 14.0;

  /// 列表/网格的外边距
  static const page = 12.0;

  /// 卡片之间的间距
  static const gap = 10.0;

  /// 顶部 AppBar 高度（Flutter 默认 56，太高）
  static const appBarHeight = 42.0;

  /// 底部导航高度（原本 58，逐轮压到 46）
  static const navBarHeight = 44.0;
}

class AppTheme {
  const AppTheme._();

  static ThemeData light() {
    final scheme = ColorScheme.fromSeed(
      seedColor: AppColors.brand,
      primary: AppColors.brand,
      surface: Colors.white,
      brightness: Brightness.light,
    );
    final base = ThemeData(colorScheme: scheme, useMaterial3: true);
    return base.copyWith(
      scaffoldBackgroundColor: AppColors.bg,
      splashFactory: InkSparkle.splashFactory,
      appBarTheme: const AppBarTheme(
        backgroundColor: Colors.white,
        surfaceTintColor: Colors.transparent,
        foregroundColor: AppColors.ink,
        elevation: 0,
        scrolledUnderElevation: 0.5,
        centerTitle: false,
        // 顶栏压到 44（Flutter 默认 56 太占地方），图标与标题同步缩小
        toolbarHeight: AppSpacing.appBarHeight,
        iconTheme: IconThemeData(size: 21, color: AppColors.ink),
        actionsIconTheme: IconThemeData(size: 21, color: AppColors.inkSoft),
        titleTextStyle: TextStyle(
          fontSize: 16.5,
          fontWeight: FontWeight.w700,
          color: AppColors.ink,
        ),
      ),
      cardTheme: CardThemeData(
        color: Colors.white,
        elevation: 0,
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppRadius.card)),
      ),
      // 输入框：**默认无边框的浅底胶囊**，只有聚焦时才出现一圈品牌色细边。
      // 原来每个框都描一圈灰边，一屏十来个框看着很"表格化"，不够简约；
      // 浅底 + 无边框留白更多，聚焦态也更明确。
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: AppColors.bg,
        contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
        isDense: true,
        hintStyle: const TextStyle(color: AppColors.inkFaint, fontSize: 13),
        // 字数统计平时不显示（10/40 这种小字很吵），只在接近上限时出现
        counterStyle: const TextStyle(color: AppColors.inkFaint, fontSize: 11),
        labelStyle: const TextStyle(color: AppColors.inkSoft, fontSize: 12.5),
        floatingLabelStyle: const TextStyle(color: AppColors.brandDark, fontSize: 12.5),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(AppRadius.field),
          borderSide: BorderSide.none,
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(AppRadius.field),
          borderSide: BorderSide.none,
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(AppRadius.field),
          borderSide: const BorderSide(color: AppColors.brand, width: 1.4),
        ),
        errorBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(AppRadius.field),
          borderSide: const BorderSide(color: AppColors.price, width: 1.2),
        ),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          backgroundColor: AppColors.brand,
          foregroundColor: Colors.white,
          minimumSize: const Size(0, 48),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppRadius.field)),
          textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          foregroundColor: AppColors.brand,
          minimumSize: const Size(0, 46),
          side: const BorderSide(color: AppColors.brand),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppRadius.field)),
        ),
      ),
      chipTheme: base.chipTheme.copyWith(
        backgroundColor: Colors.white,
        side: BorderSide.none,
        shape: const StadiumBorder(),
        labelStyle: const TextStyle(fontSize: 13, color: AppColors.inkSoft),
        selectedColor: AppColors.brandSoft,
      ),
      dividerTheme: const DividerThemeData(color: AppColors.line, thickness: 1, space: 1),
      snackBarTheme: SnackBarThemeData(
        behavior: SnackBarBehavior.floating,
        backgroundColor: AppColors.ink,
        contentTextStyle: const TextStyle(color: Colors.white),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      ),
      progressIndicatorTheme: const ProgressIndicatorThemeData(
        color: AppColors.brand,
        linearTrackColor: AppColors.line,
      ),
      bottomSheetTheme: const BottomSheetThemeData(
        backgroundColor: Colors.white,
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
        ),
      ),
    );
  }

  /// 卡片阴影：比 Card 默认更轻，避免页面发灰。
  static List<BoxShadow> get softShadow => const [
        BoxShadow(
          color: Color(0x0F000000),
          blurRadius: 12,
          offset: Offset(0, 4),
        ),
      ];
}
