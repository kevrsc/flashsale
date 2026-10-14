This document exists to help a lead architect walk one seckill qualification in a discussion, with the diagram as the shared picture.

Chosen model: Explanation (Conceptual). The lead states the claim, the diagram is the context, and "One qualification" is the concrete sequence. "Settled choices" closes the discussion after that sequence. Word counts are from `word_metrics.py`: 728 words in a single preamble, because the script splits on markdown headings and this file is HTML. The same counter on the source: style and head 190 (lines 1–28), lead 40, diagram 269 (lines 34–87), One qualification 137 (lines 90–97), Settled choices 81 (lines 100–111).

| Pass      | Original Text | Revised Text | Changes |
| --------- | ------------- | ------------ | ------- |
| structure | Diagram arrow block (49 of the diagram's 269 words): `M780 108 V200`, `M845 108 V200`, `M900 108 V200`, and `M500 422 H250` ("load / start / close") | MOVE the first three strokes so they end on Redis, Kafka, and Postgres; MOVE the load/start/close stroke so it ends on Redis | The sale application spans x 720–970, so a vertical drop lands on Postgres (x 770–950). Redis is x 250–450 and Kafka is x 520–700, and neither receives a stroke from the sale application. The load/start/close stroke ends at y=422; Redis ends at y=288. The Kafka-to-inventory stroke (`M610 288 V390`) already meets Inventory. 0 words. |
| structure | §One qualification, item 5, "A missing order fails the build." (6 of that item's 27 words) | CUT | The sentence is a build gate. This page is the discussion diagram and the qualification walkthrough; the spine is the build contract. Item 5 still says a pending document is published again until the order row exists, and that the consumer then asks the script to drop it. Saves 6 words. |
| structure | §One qualification, item 6, "Close is the only publisher of the close report, and a second close repeats the same remaining count." (18 of that item's 27 words) | MOVE to a sentence after the list, outside the numbered steps | The list is one qualification, in order. This sentence is the close path, which the diagram already labels load / start / close. Standing after the list keeps the fact on the page and leaves it out of the qualification numbering. 0 words. |
| structure | Lead (40 words); the remaining qualification steps (113 words after the cut and the move); the diagram boxes and labels (the 269-word diagram, arrow endpoints aside); §Settled choices (81 words) | PRESERVE | These carry the discussion for a human reader: the three-sentence claim, the picture, the spoken sequence, and the ruled-out column. The lead does not restate the six steps. The table's second column is the alternative each choice rejects, which the list does not carry. 0 words. |

**Summary**

4 recommendations. If all are accepted, the page drops by 6 words, 0.8% of 728, to 722. No length target was provided.

The numbered list goes from 137 words to 113. The 18-word close-report sentence stays on the page after the list. The diagram stays 269 words; only arrow endpoints move. The 190-word style block is unchanged.

Comprehension trade-offs: this page no longer says a missing order fails the build. Item 5 still follows a pending document until the order row exists, then drops it. The close-report rule leaves the numbered sequence and remains the next sentence, so the list stays one qualification. The lead, the boxes, the remaining steps, and the settled-choices table stay, so the claim, the picture, and the forks the discussion is for remain in the room.
