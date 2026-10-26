package org.platform.demo;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.platform.kernel.Uncertain;

/**
 * 从 journal 重放出的 run 既有进度：跑没跑过、跑到哪、轨迹长什么样、哪些步悬空、
 * 每个调用已发起几次（attemptByToolUse，按 toolUseId）、哪些悬空步已裁定过（awaiting_human / replayed_from_receipt 都算）。
 * lastDoneSeq 只是续跑的起步位置，不参与任何身份判断。
 */
record Replay(boolean started, boolean finished, String answer, int lastDoneSeq,
              List<Msg> messages, List<Uncertain> uncertain,
              Set<String> adjudicatedToolUse, Map<String, Integer> attemptByToolUse) {
}
