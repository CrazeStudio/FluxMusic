import 'package:flutter/material.dart';

/// Lightweight shimmer placeholder (no extra package).
class Skeleton extends StatefulWidget {
  const Skeleton({super.key, this.width, this.height = 16, this.radius = 8});
  final double? width;
  final double height;
  final double radius;

  @override
  State<Skeleton> createState() => _SkeletonState();
}

class _SkeletonState extends State<Skeleton> with SingleTickerProviderStateMixin {
  late final AnimationController _controller =
      AnimationController(vsync: this, duration: const Duration(milliseconds: 1200))..repeat();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _controller,
      builder: (context, _) {
        final t = _controller.value;
        return Container(
          width: widget.width,
          height: widget.height,
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(widget.radius),
            gradient: LinearGradient(
              begin: Alignment(-1 + 2 * t, 0),
              end: Alignment(1 + 2 * t, 0),
              colors: const [Color(0xFF1E1E1E), Color(0xFF2E2E2E), Color(0xFF1E1E1E)],
            ),
          ),
        );
      },
    );
  }
}

/// A track-row skeleton for search/home loading states.
class TrackRowSkeleton extends StatelessWidget {
  const TrackRowSkeleton({super.key});

  @override
  Widget build(BuildContext context) {
    return const Padding(
      padding: EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Row(children: [
        Skeleton(width: 52, height: 52, radius: 10),
        SizedBox(width: 12),
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Skeleton(height: 14),
            SizedBox(height: 8),
            Skeleton(width: 120, height: 12),
          ]),
        ),
      ]),
    );
  }
}
