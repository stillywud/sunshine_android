import 'package:flutter/material.dart';

class DlnaModeSelector extends StatelessWidget {
  const DlnaModeSelector({
    super.key,
    this.lowLatency = true,
    required this.onChanged,
  });

  final bool lowLatency;
  final ValueChanged<bool>? onChanged;

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SegmentedButton<bool>(
          segments: const [
            ButtonSegment(value: true, label: Text('短分片（低延迟）')),
            ButtonSegment(value: false, label: Text('连续 TS（兼容）')),
          ],
          selected: {lowLatency},
          onSelectionChanged: onChanged == null
              ? null
              : (selection) => onChanged!(selection.single),
          showSelectedIcon: false,
          style: SegmentedButton.styleFrom(
            textStyle: const TextStyle(fontSize: 13),
            visualDensity: VisualDensity.compact,
            padding: const EdgeInsets.symmetric(horizontal: 8),
          ),
        ),
        const SizedBox(height: 4),
        Text(
          '短分片需接收端支持 HLS；不支持时请选连续 TS（兼容）。',
          style: TextStyle(
            fontSize: 12,
            color: Theme.of(context).colorScheme.onSurfaceVariant,
          ),
        ),
      ],
    );
  }
}
