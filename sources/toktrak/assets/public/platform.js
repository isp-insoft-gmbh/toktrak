const rawPlatform = navigator.userAgentData?.platform ?? navigator.platform ?? "";
const platform = /win/i.test(rawPlatform)
  ? "win32"
  : /mac/i.test(rawPlatform)
    ? "darwin"
    : /linux/i.test(rawPlatform)
      ? "linux"
      : "";

if (platform) {
  for (const instructions of document.querySelectorAll("[data-platform]")) {
    instructions.hidden = instructions.dataset.platform !== platform;
  }
  for (const status of document.querySelectorAll("[data-platform-status]")) {
    status.textContent = `Detected ${platform === "win32" ? "Windows" : platform === "darwin" ? "macOS" : "Linux"}.`;
  }
}

const download = document.querySelector("[data-download-tracker]");
if (download) {
  download.addEventListener("click", () => {
    const source = document.getElementById("tracker-script");
    if (!(source instanceof HTMLTextAreaElement)) return;
    const url = URL.createObjectURL(new Blob([source.value], { type: "text/javascript" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = "toktrak.mjs";
    link.hidden = true;
    document.body.append(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 0);
  });
}
