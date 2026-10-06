package com.tingxia.audio.ui.components

/**
 * 占位：**迷你播放器条 AudioPlayerBar 目前没有任何测试覆盖。**
 *
 * 这条曾经标成 `@Ignore("PlayerController 构造签名变更,需重建 ExoPlayer mock")`，
 * 但它其实是个**空类** —— 里面一个 `@Test` 都没有。类体是被整段删掉的，不是 mock
 * 构造对不上。那条理由同样是误导性的。
 *
 * 值得补的理由：这个条子承载 resume/play 的区分（恢复播放位置 vs 从头播），
 * 逻辑容易退化且退化后不报错 —— 表现为"点续播总是从头开始"，用户很难归因。
 *
 * 恢复需要从零写用例。届时请把本文件替换成真正的测试并删掉这段说明。
 */
class AudioPlayerBarTest