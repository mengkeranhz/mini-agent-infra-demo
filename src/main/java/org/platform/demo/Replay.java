package org.platform.demo;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.platform.kernel.Uncertain;

/**
 * 从 journal 重放出的 run 既有进度：跑没跑过、跑到哪、轨迹长什么样、哪些步悬空、
 * 每步已发起几次（maxAttempt）、哪些悬空步已裁定过（awaiting_human / replayed_from_receipt 都算）。
 */
record Replay(boolean started, boolean finished, String answer, int lastDoneSeq,
              List<Msg> messages, List<Uncertain> uncertain,
              Set<Integer> adjudicated, Map<Integer, Integer> maxAttempt) {
}
