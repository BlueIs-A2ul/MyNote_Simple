package com.mynote.app.data.ai

import org.json.JSONObject

class DeepSeekDriver : AiWebDriver {

    override val id: String = "deepseek"
    override val displayName: String = "DeepSeek"
    override val homeUrl: String = "https://chat.deepseek.com/"

    override fun chatUrl(remoteChatId: String): String =
        "https://chat.deepseek.com/a/chat/s/$remoteChatId"

    override fun parseChatId(url: String): String? =
        CHAT_ID_REGEX.find(url)?.groupValues?.get(1)

    override fun loginCheckJs(): String = LOGIN_CHECK_JS

    override fun newChatJs(): String = NEW_CHAT_JS

    override fun sendMessageJs(text: String): String {
        val quoted = JSONObject.quote(text)
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")
        return "window.__mynoteText = " + quoted + ";\n" + SEND_MESSAGE_JS
    }

    override fun observeReplyJs(): String = OBSERVE_REPLY_JS

    override fun stopObservingJs(): String = STOP_OBSERVING_JS

    override fun stopGeneratingJs(): String = STOP_GENERATING_JS

    private companion object {
        val CHAT_ID_REGEX = Regex("""/a/chat/s/([\w-]+)""")

        /**
         * 结构定位辅助（内嵌进各脚本，经 $ACTION_BUTTON_HELPERS 插值）：
         * DeepSeek 当前页面的发送/停止按钮是无文字、无 aria-label 的 SVG 图标按钮
         * （文案只存在于 hover 才渲染的 Tooltip），且页面按 UA 判移动端时回车不发送。
         * 发送/停止共用一个槽位，是输入框祖先容器内唯一圆形（ds-button--circle）图标按钮。
         * 选择器来源：chat.deepseek.com 线上 bundle（main.*.js 的 Button/f2 组件）。
         */
        val ACTION_BUTTON_HELPERS = """
            function __mnFindInput() {
              var list = document.querySelectorAll('textarea');
              for (var i = 0; i < list.length; i++) {
                var el = list[i];
                if (el.offsetParent !== null && !el.disabled) return el;
              }
              return null;
            }
            function __mnIconButtons(scope) {
              var out = [];
              var list = scope.querySelectorAll('button, [role="button"]');
              for (var i = 0; i < list.length; i++) {
                var el = list[i];
                if (el.offsetParent === null) continue;
                if (el.getAttribute('aria-disabled') === 'true' || el.disabled) continue;
                var cls = typeof el.className === 'string' ? el.className : '';
                if (cls.indexOf('ds-button') < 0 || cls.indexOf('ds-button--disabled') >= 0) continue;
                if (!el.querySelector('svg')) continue;
                out.push(el);
              }
              return out;
            }
            function __mnActionButton(input) {
              if (!input) return null;
              var container = input.parentElement;
              for (var i = 0; i < 12 && container; i++) {
                var list = __mnIconButtons(container);
                if (list.length) {
                  for (var k = list.length - 1; k >= 0; k--) {
                    if (String(list[k].className || '').indexOf('circle') >= 0) return list[k];
                  }
                  return list[list.length - 1];
                }
                container = container.parentElement;
              }
              return null;
            }
        """.trimIndent()

        val LOGIN_CHECK_JS = """
            (function () {
              function __emit(type, payload) { try { if (window.__mynote && window.__mynote.emit) window.__mynote.emit(type, payload); } catch (e) {} }
              var list = document.querySelectorAll('textarea');
              var ok = false;
              for (var i = 0; i < list.length; i++) {
                var el = list[i];
                if (el.offsetParent !== null && !el.disabled) { ok = true; break; }
              }
              __emit('loginState', { loggedIn: ok });
            })();
        """.trimIndent()

        val NEW_CHAT_JS = """
            (function () {
              function __emit(type, payload) { try { if (window.__mynote && window.__mynote.emit) window.__mynote.emit(type, payload); } catch (e) {} }
              var tries = 0;
              var exact = null;
              var secondary = null;
              var timer = setInterval(function () {
                var nodes = document.querySelectorAll('button, [role="button"]');
                for (var i = 0; i < nodes.length; i++) {
                  var el = nodes[i];
                  if (el.offsetParent === null) continue;
                  var t = (el.textContent || '').trim();
                  if (t === '开启新对话') { exact = el; break; }
                  if (t === '新对话' && !secondary) { secondary = el; }
                }
                var target = exact || secondary;
                if (target) { target.click(); clearInterval(timer); return; }
                if (++tries > 20) clearInterval(timer);
              }, 300);
            })();
        """.trimIndent()

        val SEND_MESSAGE_JS = """
            (function () {
              function __emit(type, payload) { try { if (window.__mynote && window.__mynote.emit) window.__mynote.emit(type, payload); } catch (e) {} }
              $ACTION_BUTTON_HELPERS
              var payload = window.__mynoteText;
              window.__mynoteText = null;
              function findSendByLabel(input) {
                var container = input.parentElement;
                for (var i = 0; i < 8 && container; i++) {
                  var btns = container.querySelectorAll('button, [role="button"]');
                  var enabled = [];
                  for (var j = 0; j < btns.length; j++) {
                    var b = btns[j];
                    if (b.offsetParent !== null && b.getAttribute('aria-disabled') !== 'true' && !b.disabled) enabled.push(b);
                  }
                  for (var k = 0; k < enabled.length; k++) {
                    var label = ((enabled[k].getAttribute('aria-label') || '') + (enabled[k].textContent || '')).toLowerCase();
                    if (label.indexOf('send') >= 0 || label.indexOf('发送') >= 0) return enabled[k];
                  }
                  container = container.parentElement;
                }
                return null;
              }
              var input = __mnFindInput();
              if (!input) {
                __emit('replyError', { reason: '未找到输入框，请显示网页手动发送' });
                return;
              }
              try {
                input.focus();
                var setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set;
                setter.call(input, payload);
                input.dispatchEvent(new Event('input', { bubbles: true }));
                input.dispatchEvent(new Event('change', { bubbles: true }));
              } catch (e) {
                __emit('replyError', { reason: '写入输入框失败，请显示网页手动发送' });
                return;
              }
              setTimeout(function () {
                var current = __mnFindInput();
                if (!current) {
                  __emit('replyError', { reason: '输入框已消失，请显示网页手动发送' });
                  return;
                }
                var btn = findSendByLabel(current) || __mnActionButton(current);
                if (btn) { btn.click(); return; }
                current.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
              }, 500);
            })();
        """.trimIndent()

        val OBSERVE_REPLY_JS = """
            (function () {
              function __emit(type, payload) { try { if (window.__mynote && window.__mynote.emit) window.__mynote.emit(type, payload); } catch (e) {} }
              $ACTION_BUTTON_HELPERS
              if (window.__mynoteObserver) { window.__mynoteObserver.disconnect(); window.__mynoteObserver = null; }
              if (window.__mynoteTimer) { clearInterval(window.__mynoteTimer); window.__mynoteTimer = null; }
              function findStop() {
                var nodes = document.querySelectorAll('[role="button"], button');
                for (var i = 0; i < nodes.length; i++) {
                  var t = (nodes[i].textContent || '').trim().toLowerCase();
                  var a = (nodes[i].getAttribute('aria-label') || '').toLowerCase();
                  if (t.indexOf('停止') >= 0 || a.indexOf('停止') >= 0 || t.indexOf('stop') >= 0 || a.indexOf('stop') >= 0) return nodes[i];
                }
                return __mnActionButton(__mnFindInput());
              }
              function targets() {
                return document.querySelectorAll('[class*="markdown"]');
              }
              function readLast() {
                var list = targets();
                if (!list.length) return '';
                var el = list[list.length - 1];
                return (el.innerText || '').replace(/\s+$/, '');
              }
              var beforeCount = targets().length;
              var initialText = readLast();
              var lastText = initialText;
              var lastChange = Date.now();
              var started = false;
              var finished = false;
              var lastScan = 0;
              function finish(ok, reason) {
                if (finished) return;
                finished = true;
                if (window.__mynoteObserver) { window.__mynoteObserver.disconnect(); window.__mynoteObserver = null; }
                if (window.__mynoteTimer) { clearInterval(window.__mynoteTimer); window.__mynoteTimer = null; }
                if (ok) { __emit('replyDone', { text: lastText }); }
                else { __emit('replyError', { reason: reason || '回答超时' }); }
              }
              function scan() {
                if (finished) return;
                var list = targets();
                var count = list.length;
                var text = '';
                if (count) {
                  var el = list[count - 1];
                  text = (el.innerText || '').replace(/\s+$/, '');
                }
                if (!started && text !== '' && (count > beforeCount || text !== initialText)) {
                  started = true;
                  lastText = text;
                  lastChange = Date.now();
                  __emit('replyChunk', { text: text });
                } else if (started && text !== '' && text !== lastText) {
                  lastText = text;
                  lastChange = Date.now();
                  __emit('replyChunk', { text: text });
                }
                var stop = findStop();
                if (started && !stop && Date.now() - lastChange > 1200) { finish(true); return; }
                if (started && Date.now() - lastChange > 60000) { finish(false, '回答超时'); return; }
                if (!started && Date.now() - lastChange > 60000) { finish(false, '未收到回答，请显示网页检查'); }
              }
              function tick(force) {
                if (finished) return;
                var now = Date.now();
                if (!force && now - lastScan < 150) return;
                lastScan = now;
                scan();
              }
              window.__mynoteObserver = new MutationObserver(function () { tick(false); });
              window.__mynoteObserver.observe(document.body, { childList: true, subtree: true, characterData: true });
              window.__mynoteTimer = setInterval(function () { tick(true); }, 500);
            })();
        """.trimIndent()

        val STOP_OBSERVING_JS = """
            (function () {
              function __emit(type, payload) { try { if (window.__mynote && window.__mynote.emit) window.__mynote.emit(type, payload); } catch (e) {} }
              if (window.__mynoteObserver) { window.__mynoteObserver.disconnect(); window.__mynoteObserver = null; }
              if (window.__mynoteTimer) { clearInterval(window.__mynoteTimer); window.__mynoteTimer = null; }
            })();
        """.trimIndent()

        val STOP_GENERATING_JS = """
            (function () {
              function __emit(type, payload) { try { if (window.__mynote && window.__mynote.emit) window.__mynote.emit(type, payload); } catch (e) {} }
              $ACTION_BUTTON_HELPERS
              var nodes = document.querySelectorAll('[role="button"], button');
              for (var i = 0; i < nodes.length; i++) {
                var t = (nodes[i].textContent || '').trim().toLowerCase();
                var a = (nodes[i].getAttribute('aria-label') || '').toLowerCase();
                if (t.indexOf('停止') >= 0 || a.indexOf('停止') >= 0 || t.indexOf('stop') >= 0 || a.indexOf('stop') >= 0) { nodes[i].click(); return; }
              }
              var btn = __mnActionButton(__mnFindInput());
              if (btn) btn.click();
            })();
        """.trimIndent()
    }
}
