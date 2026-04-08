(function () {
  const storePrefix = "wikiVizDemo:";
  function safeParse(value, fallback) {
    try { return value ? JSON.parse(value) : fallback; } catch (error) { return fallback; }
  }
  window.DemoState = {
    read(key, fallback) { return safeParse(localStorage.getItem(storePrefix + key), fallback); },
    write(key, value) { localStorage.setItem(storePrefix + key, JSON.stringify(value)); },
    merge(key, patch) {
      const current = this.read(key, {});
      const next = Object.assign({}, current, patch);
      this.write(key, next);
      return next;
    },
    clear(key) { localStorage.removeItem(storePrefix + key); }
  };
  window.DemoUI = {
    toggleDetails(pageKey, checked) {
      DemoState.merge(pageKey, { showDetails: checked });
      document.querySelectorAll("[data-detail-block]").forEach(function (block) {
        block.classList.toggle("hidden", !checked);
      });
    },
    bindDetailsToggle(pageKey, selector) {
      const input = document.querySelector(selector);
      if (!input) return;
      const state = DemoState.read(pageKey, { showDetails: true });
      input.checked = state.showDetails !== false;
      this.toggleDetails(pageKey, input.checked);
      input.addEventListener("change", function () { DemoUI.toggleDetails(pageKey, input.checked); });
    },
    renderJson(selector, data) {
      const el = document.querySelector(selector);
      if (el) el.textContent = JSON.stringify(data, null, 2);
    },
    setText(selector, text) {
      const el = document.querySelector(selector);
      if (el) el.textContent = text;
    },
    openModal(title, html) {
      const backdrop = document.getElementById("modal-backdrop");
      if (!backdrop) return;
      backdrop.classList.add("show");
      backdrop.querySelector("[data-modal-title]").textContent = title;
      backdrop.querySelector("[data-modal-body]").innerHTML = html;
    },
    closeModal() {
      const backdrop = document.getElementById("modal-backdrop");
      if (backdrop) backdrop.classList.remove("show");
    },
    bindModal() {
      const backdrop = document.getElementById("modal-backdrop");
      if (!backdrop) return;
      backdrop.addEventListener("click", function (event) {
        if (event.target === backdrop || event.target.hasAttribute("data-close-modal")) DemoUI.closeModal();
      });
    },
    sleep(ms) { return new Promise(function (resolve) { setTimeout(resolve, ms); }); }
  };
  window.DemoFlow = {
    async runSequence(options) {
      const config = Object.assign({
        steps: [],
        speed: 1,
        onStep: function () {},
        shouldPause: function () { return false; },
        shouldStop: function () { return false; }
      }, options || {});
      for (let index = 0; index < config.steps.length; index += 1) {
        while (config.shouldPause()) await DemoUI.sleep(120);
        if (config.shouldStop()) return;
        await config.onStep(config.steps[index], index);
        const delay = Math.max(180, config.steps[index].delay / Math.max(0.25, config.speed || 1));
        await DemoUI.sleep(delay);
      }
    }
  };
  window.DemoViewport = {
    bindDraggableScroll(wrapperSelector) {
      const wrap = typeof wrapperSelector === "string" ? document.querySelector(wrapperSelector) : wrapperSelector;
      if (!wrap || wrap.dataset.dragBound === "true") return;
      wrap.dataset.dragBound = "true";
      let dragging = false;
      let startX = 0;
      let startY = 0;
      let startLeft = 0;
      let startTop = 0;
      wrap.addEventListener("mousedown", function (event) {
        if (event.target.closest(".node")) return;
        dragging = true;
        startX = event.clientX;
        startY = event.clientY;
        startLeft = wrap.scrollLeft;
        startTop = wrap.scrollTop;
        wrap.classList.add("dragging");
      });
      window.addEventListener("mousemove", function (event) {
        if (!dragging) return;
        wrap.scrollLeft = startLeft - (event.clientX - startX);
        wrap.scrollTop = startTop - (event.clientY - startY);
      });
      window.addEventListener("mouseup", function () {
        if (!dragging) return;
        dragging = false;
        wrap.classList.remove("dragging");
      });
      wrap.addEventListener("mouseleave", function () {
        if (!dragging) return;
        dragging = false;
        wrap.classList.remove("dragging");
      });
    },
    centerNode(wrapperSelector, boardSelector, nodeSelector) {
      const wrap = typeof wrapperSelector === "string" ? document.querySelector(wrapperSelector) : wrapperSelector;
      const board = typeof boardSelector === "string" ? document.querySelector(boardSelector) : boardSelector;
      const node = typeof nodeSelector === "string" ? document.querySelector(nodeSelector) : nodeSelector;
      if (!wrap || !board || !node) return;
      const nodeLeft = node.offsetLeft;
      const nodeTop = node.offsetTop;
      const targetLeft = Math.max(0, nodeLeft - (wrap.clientWidth / 2) + (node.clientWidth / 2));
      const targetTop = Math.max(0, nodeTop - (wrap.clientHeight / 2) + (node.clientHeight / 2));
      wrap.scrollTo({ left: targetLeft, top: targetTop, behavior: "smooth" });
    }
  };
  window.addEventListener("DOMContentLoaded", function () { DemoUI.bindModal(); });
})();
