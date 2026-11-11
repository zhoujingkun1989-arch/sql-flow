/**
 * Inline CSS bundle text injected directly into same-origin `<iframe>` sandboxes
 * so `SQLFlow.init(...)` renders zero-FOUC, style-isolated widgets immediately in both
 * real browsers and headless JSDOM test environments.
 */

import widgetCssText from "./widget.css?inline";

export const WIDGET_INLINE_CSS: string = widgetCssText;
