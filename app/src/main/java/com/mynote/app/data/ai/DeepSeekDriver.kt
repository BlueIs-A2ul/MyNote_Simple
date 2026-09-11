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

    override fun sendMessageJs(text: String): String =
        "window.__mynoteText = " + JSONObject.quote(text) + ";\n" + SEND_MESSAGE_JS

    override fun observeReplyJs(): String = OBSERVE_REPLY_JS

    override fun stopObservingJs(): String = STOP_OBSERVING_JS

    override fun stopGeneratingJs(): String = STOP_GENERATING_JS

    private companion object {
        val CHAT_ID_REGEX = Regex("""/a/chat/s/([\w-]+)""")

        val LOGIN_CHECK_JS = """
            (function () {
              var list = document.querySelectorAll('textarea');
              var ok = false;
              for (var i = 0; i < list.length; i++) {
                var el = list[i];
                if (el.offsetParent !== null && !el.disabled) { ok = true; break; }
              }
              window.__mynote.emit('loginState', { loggedIn: ok });
            })();
        """.trimIndent()

        val NEW_CHAT_JS = """
            (function () {
              var tries = 0;
              var timer = setInterval(function () {
                var nodes = document.querySelectorAll('button, [role="button"], div, span');
                for (var i = 0; i < nodes.length; i++) {
                  var el = nodes[i];
                  var t = (el.textContent || '').trim();
                  if ((t === '开启新对话' || t === '新对话') && el.offsetParent !== null) {
                    el.click();
                    clearInterval(timer);
                    return;
                  }
                }
                if (++tries > 20) clearInterval(timer);
              }, 300);
            })();
        """.trimIndent()

        val SEND_MESSAGE_JS = """
            (function () {
              var payload = window.__mynoteText;
              function findInput() {
                var list = document.querySelectorAll('textarea');
                for (var i = 0; i < list.length; i++) {
                  var el = list[i];
                  if (el.offsetParent !== null && !el.disabled) return el;
                }
                return null;
              }
              function findSend(input) {
                var container = input.parentElement;
                for (var i = 0; i < 8 && container; i++) {
                  var btns = container.querySelectorAll('button, [role="button"]');
                  var enabled = [];
                  for (var j = 0; j < btns.length; j++) {
                    var b = btns[j];
                    if (b.offsetParent !== null && b.getAttribute('aria-disabled') !== 'true' && !b.disabled) enabled.push(b);
                  }
                  if (enabled.length) {
                    for (var k = 0; k < enabled.length; k++) {
                      var label = ((enabled[k].getAttribute('aria-label') || '') + (enabled[k].textContent || '')).toLowerCase();
                      if (label.indexOf('send') >= 0 || label.indexOf('发送') >= 0) return enabled[k];
                    }
                    return enabled[enabled.length - 1];
                  }
                  container = container.parentElement;
                }
                return null;
              }
              var input = findInput();
              if (!input) {
                window.__mynote.emit('replyError', { reason: '未找到输入框，请显示网页手动发送' });
                return;
              }
              input.focus();
              var setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set;
              setter.call(input, payload);
              input.dispatchEvent(new Event('input', { bubbles: true }));
              input.dispatchEvent(new Event('change', { bubbles: true }));
              setTimeout(function () {
                var btn = findSend(input);
                if (btn) { btn.click(); return; }
                input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
              }, 500);
            })();
        """.trimIndent()

        val OBSERVE_REPLY_JS = """
            (function () {
              if (window.__mynoteObserver) { window.__mynoteObserver.disconnect(); window.__mynoteObserver = null; }
              if (window.__mynoteTimer) { clearInterval(window.__mynoteTimer); window.__mynoteTimer = null; }
              function findStop() {
                var nodes = document.querySelectorAll('[role="button"], button');
                for (var i = 0; i < nodes.length; i++) {
                  var t = (nodes[i].textContent || '').trim();
                  var a = nodes[i].getAttribute('aria-label') || '';
                  if (t.indexOf('停止') >= 0 || a.indexOf('停止') >= 0) return nodes[i];
                }
                return null;
              }
              function targets() {
                return document.querySelectorAll('[class*="markdown"]');
              }
              var beforeCount = targets().length;
              var lastText = '';
              var lastChange = Date.now();
              var started = false;
              var finished = false;
              function finish(ok, reason) {
                if (finished) return;
                finished = true;
                if (window.__mynoteObserver) { window.__mynoteObserver.disconnect(); window.__mynoteObserver = null; }
                if (window.__mynoteTimer) { clearInterval(window.__mynoteTimer); window.__mynoteTimer = null; }
                if (ok) { window.__mynote.emit('replyDone', { text: lastText }); }
                else { window.__mynote.emit('replyError', { reason: reason || '回答超时' }); }
              }
              function tick() {
                var list = targets();
                if (list.length > beforeCount) {
                  var el = list[list.length - 1];
                  var text = (el.innerText || '').replace(/\s+$/, '');
                  if (text !== lastText) {
                    lastText = text;
                    lastChange = Date.now();
                    started = true;
                    window.__mynote.emit('replyChunk', { text: text });
                  }
                }
                var stop = findStop();
                if (started && !stop && Date.now() - lastChange > 1200) { finish(true); return; }
                if (started && Date.now() - lastChange > 60000) { finish(false, '回答超时'); return; }
                if (!started && Date.now() - lastChange > 60000) { finish(false, '未收到回答，请显示网页检查'); }
              }
              window.__mynoteObserver = new MutationObserver(tick);
              window.__mynoteObserver.observe(document.body, { childList: true, subtree: true, characterData: true });
              window.__mynoteTimer = setInterval(tick, 500);
            })();
        """.trimIndent()

        val STOP_OBSERVING_JS = """
            (function () {
              if (window.__mynoteObserver) { window.__mynoteObserver.disconnect(); window.__mynoteObserver = null; }
              if (window.__mynoteTimer) { clearInterval(window.__mynoteTimer); window.__mynoteTimer = null; }
            })();
        """.trimIndent()

        val STOP_GENERATING_JS = """
            (function () {
              var nodes = document.querySelectorAll('[role="button"], button');
              for (var i = 0; i < nodes.length; i++) {
                var t = (nodes[i].textContent || '').trim();
                var a = nodes[i].getAttribute('aria-label') || '';
                if (t.indexOf('停止') >= 0 || a.indexOf('停止') >= 0) { nodes[i].click(); return; }
              }
            })();
        """.trimIndent()
    }
}
