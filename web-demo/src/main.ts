import "./style.css";
import { xtScanCode } from "./scancodes";

type DemoApi = {
  createDemoMachine: (u18: Uint8Array, u19: Uint8Array, floppy: Uint8Array) => string;
  runSlice: (maxInstructions: number) => boolean;
  pollFrame: (dest: Int32Array) => boolean;
  frameWidth: () => number;
  frameHeight: () => number;
  sendScanCode: (code: number) => void;
  instructionCount: () => number;
};

const canvas = document.getElementById("screen") as HTMLCanvasElement;
const statusEl = document.getElementById("status") as HTMLParagraphElement;
const ctx = canvas.getContext("2d", { alpha: false });

function setStatus(text: string, error = false) {
  statusEl.textContent = text;
  statusEl.dataset.state = error ? "error" : "ok";
}

async function fetchBytes(path: string): Promise<Uint8Array> {
  const res = await fetch(path);
  if (!res.ok) throw new Error(`${path}: ${res.status}`);
  return new Uint8Array(await res.arrayBuffer());
}

async function loadDemoApi(): Promise<DemoApi> {
  const url = `${import.meta.env.BASE_URL}k8086.js`;
  await new Promise<void>((resolve, reject) => {
    const existing = document.querySelector(`script[src="${url}"]`);
    if (existing) {
      resolve();
      return;
    }
    const script = document.createElement("script");
    script.src = url;
    script.onload = () => resolve();
    script.onerror = () => reject(new Error(`Failed to load ${url}`));
    document.head.appendChild(script);
  });
  const pending = (globalThis as unknown as { ["k8086-web"]?: Promise<DemoApi> })["k8086-web"];
  if (!pending) {
    throw new Error("Wasm module did not attach to globalThis['k8086-web']");
  }
  const api = await pending;
  if (typeof api.createDemoMachine !== "function") {
    throw new Error("Wasm module did not export createDemoMachine");
  }
  return api;
}

function paintArgb(argb: Int32Array, width: number, height: number) {
  if (!ctx || width <= 0 || height <= 0) return;
  if (canvas.width !== width || canvas.height !== height) {
    canvas.width = width;
    canvas.height = height;
  }
  const image = ctx.createImageData(width, height);
  const rgba = image.data;
  for (let i = 0; i < width * height; i++) {
    const p = argb[i] >>> 0;
    const o = i * 4;
    rgba[o] = (p >>> 16) & 0xff;
    rgba[o + 1] = (p >>> 8) & 0xff;
    rgba[o + 2] = p & 0xff;
    rgba[o + 3] = 0xff;
  }
  ctx.putImageData(image, 0, 0);
}

async function main() {
  if (!ctx) {
    setStatus("Canvas is unavailable in this browser.", true);
    return;
  }
  try {
    setStatus("Loading Wasm + ROMs…");
    const [api, u18, u19, floppy] = await Promise.all([
      loadDemoApi(),
      fetchBytes(`${import.meta.env.BASE_URL}assets/u18.bin`),
      fetchBytes(`${import.meta.env.BASE_URL}assets/u19.bin`),
      fetchBytes(`${import.meta.env.BASE_URL}assets/fd.img`),
    ]);
    const err = api.createDemoMachine(u18, u19, floppy);
    if (err) throw new Error(err);

    const pixels = new Int32Array(640 * 200);
    let running = true;
    let budget = 250_000;
    let focused = false;

    canvas.addEventListener("pointerdown", () => canvas.focus());
    canvas.addEventListener("focus", () => {
      focused = true;
    });
    canvas.addEventListener("blur", () => {
      focused = false;
    });
    window.addEventListener("keydown", (e) => {
      if (!focused) return;
      const code = xtScanCode(e.code, true);
      if (code == null) return;
      e.preventDefault();
      if (!e.repeat) api.sendScanCode(code);
    });
    window.addEventListener("keyup", (e) => {
      if (!focused) return;
      const code = xtScanCode(e.code, false);
      if (code == null) return;
      e.preventDefault();
      api.sendScanCode(code);
    });

    const tick = (now: number) => {
      if (!running) return;
      const start = performance.now();
      running = api.runSlice(budget);
      const dt = performance.now() - start;
      if (dt > 14) budget = Math.max(20_000, Math.floor(budget * 0.85));
      else if (dt < 7) budget = Math.min(900_000, Math.floor(budget * 1.08));

      if (api.pollFrame(pixels)) {
        paintArgb(pixels, api.frameWidth(), api.frameHeight());
      }
      const insn = api.instructionCount();
      const focus = focused ? "keyboard on" : "click screen for keyboard";
      setStatus(
        running
          ? `running · ${insn.toLocaleString()} instructions · ${focus}`
          : `stopped · ${insn.toLocaleString()} instructions`,
      );
      if (running) requestAnimationFrame(tick);
      void now;
    };
    requestAnimationFrame(tick);
  } catch (e) {
    const message = e instanceof Error ? e.message : String(e);
    setStatus(message, true);
  }
}

void main();
