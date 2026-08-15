const button = document.querySelector("[data-copy-token]");
const token = document.querySelector(".token-secret");

if (button && token) {
  button.addEventListener("click", async () => {
    try {
      await navigator.clipboard.writeText(token.textContent);
      button.textContent = "Copied";
    } catch {
      const range = document.createRange();
      range.selectNodeContents(token);
      const selection = getSelection();
      selection.removeAllRanges();
      selection.addRange(range);
      button.textContent = "Selected — press copy";
    }
  });
}
