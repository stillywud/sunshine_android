import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sunshine_android/widgets/dlna_mode_selector.dart';

Widget selectorApp(Widget selector) {
  return MaterialApp(home: Scaffold(body: selector));
}

SegmentedButton<bool> modeButton(WidgetTester tester) {
  final finder = find.byType(SegmentedButton<bool>);
  expect(finder, findsOneWidget);
  return tester.widget<SegmentedButton<bool>>(finder);
}

void main() {
  testWidgets('defaults to short HLS segments and explains the TS fallback',
      (tester) async {
    final changes = <bool>[];
    await tester.pumpWidget(
      selectorApp(DlnaModeSelector(onChanged: changes.add)),
    );

    expect(find.text('短分片（低延迟）'), findsOneWidget);
    expect(find.text('连续 TS（兼容）'), findsOneWidget);
    expect(
      find.text('短分片需接收端支持 HLS；不支持时请选连续 TS（兼容）。'),
      findsOneWidget,
    );
    expect(modeButton(tester).selected, {true});
    expect(changes, isEmpty);
  });

  testWidgets('reflects an explicitly selected TS compatibility mode',
      (tester) async {
    await tester.pumpWidget(
      selectorApp(
        DlnaModeSelector(lowLatency: false, onChanged: (_) {}),
      ),
    );

    expect(modeButton(tester).selected, {false});
  });

  testWidgets('reports both mode changes and reflects the parent selection',
      (tester) async {
    bool lowLatency = true;
    final changes = <bool>[];
    await tester.pumpWidget(
      selectorApp(
        StatefulBuilder(
          builder: (context, setState) => DlnaModeSelector(
            lowLatency: lowLatency,
            onChanged: (selection) {
              changes.add(selection);
              setState(() => lowLatency = selection);
            },
          ),
        ),
      ),
    );

    expect(modeButton(tester).onSelectionChanged, isNotNull);
    await tester.tap(find.text('连续 TS（兼容）'));
    await tester.pumpAndSettle();
    expect(changes, [false]);
    expect(modeButton(tester).selected, {false});

    await tester.tap(find.text('短分片（低延迟）'));
    await tester.pumpAndSettle();
    expect(changes, [false, true]);
    expect(modeButton(tester).selected, {true});
  });

  for (final lowLatency in [true, false]) {
    testWidgets('disables mode changes while casting (lowLatency=$lowLatency)',
        (tester) async {
      await tester.pumpWidget(
        selectorApp(
          DlnaModeSelector(lowLatency: lowLatency, onChanged: null),
        ),
      );

      expect(modeButton(tester).onSelectionChanged, isNull);
      await tester.tap(
        find.text(lowLatency ? '连续 TS（兼容）' : '短分片（低延迟）'),
      );
      await tester.pumpAndSettle();
      expect(modeButton(tester).selected, {lowLatency});
    });
  }

  testWidgets('fits a compact DLNA section without overflow', (tester) async {
    await tester.pumpWidget(
      selectorApp(
        SizedBox(
          width: 280,
          child: DlnaModeSelector(onChanged: (_) {}),
        ),
      ),
    );

    expect(find.text('短分片（低延迟）'), findsOneWidget);
    expect(find.text('连续 TS（兼容）'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
