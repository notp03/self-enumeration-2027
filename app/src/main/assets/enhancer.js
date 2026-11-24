(function () {
  if (window.__censusEnhancerLoaded) return;
  window.__censusEnhancerLoaded = true;

  // 1. Inject Parent-Friendly Mobile CSS
  const style = document.createElement('style');
  style.innerHTML = `
    body, input, select, button, label {
      font-size: 16px !important;
      line-height: 1.5 !important;
    }
    input[type="radio"], input[type="checkbox"] {
      width: 22px !important;
      height: 22px !important;
      margin-right: 8px !important;
    }
    button, .btn, input[type="submit"], input[type="button"] {
      min-height: 48px !important;
      padding: 10px 18px !important;
      font-weight: 600 !important;
      border-radius: 8px !important;
    }
    .census-field-error {
      border: 3px solid #d93025 !important;
      background-color: #fff8f7 !important;
      box-shadow: 0 0 8px rgba(217, 48, 37, 0.4) !important;
    }
    #census-error-banner {
      position: fixed;
      top: 0; left: 0; right: 0;
      background: #d93025;
      color: #ffffff;
      padding: 14px 16px;
      font-size: 16px;
      font-weight: bold;
      z-index: 999999;
      box-shadow: 0 4px 10px rgba(0,0,0,0.3);
      display: none;
    }
  `;
  document.head.appendChild(style);

  // Top Alert Banner for Missing Fields
  const banner = document.createElement('div');
  banner.id = 'census-error-banner';
  banner.onclick = () => { banner.style.display = 'none'; };
  document.body.appendChild(banner);

  function showBanner(msg) {
    banner.textContent = msg + " (Tap to dismiss)";
    banner.style.display = 'block';
  }

  // 2. Suppress Keyboard on Dropdown-Style Inputs
  function fixDropdownInputs() {
    const inputs = document.querySelectorAll('input[type="text"], input:not([type])');
    inputs.forEach(inp => {
      const placeholder = (inp.placeholder || '').toLowerCase();
      const cls = (inp.className || '').toLowerCase();
      const role = (inp.getAttribute('role') || '').toLowerCase();

      if (
        inp.readOnly ||
        placeholder.includes('select') ||
        cls.includes('select') ||
        cls.includes('dropdown') ||
        role === 'combobox'
      ) {
        inp.setAttribute('inputmode', 'none');
      }
    });
  }

  // 3. Intercept "Proceed" / "Submit" Clicks to Catch Silent Empty Fields
  document.addEventListener('click', function (e) {
    const btn = e.target.closest('button, input[type="submit"], input[type="button"], a.btn');
    if (!btn) return;

    const btnText = (btn.innerText || btn.value || '').toLowerCase();
    if (!btnText.includes('proceed') && !btnText.includes('submit') && !btnText.includes('save') && !btnText.includes('next')) {
      return;
    }

    // Clear previous error highlights
    document.querySelectorAll('.census-field-error').forEach(el => el.classList.remove('census-field-error'));
    banner.style.display = 'none';

    const fields = Array.from(document.querySelectorAll('input, select, textarea')).filter(el => {
      if (el.type === 'hidden' || el.disabled || el.offsetParent === null) return false;
      if (el.type === 'button' || el.type === 'submit' || el.type === 'reset') return false;
      return true;
    });

    for (const field of fields) {
      const val = (field.value || '').trim();
      const isSelectDefault = field.tagName === 'SELECT' && (val === '' || val === '0' || val.toLowerCase().includes('select'));
      const isRequired = field.required || field.getAttribute('aria-required') === 'true' || field.className.toLowerCase().includes('req');

      // Check empty required fields or unfilled visible fields before Proceed
      if ((isRequired || field.tagName === 'SELECT') && (val === '' || isSelectDefault)) {
        field.classList.add('census-field-error');
        field.scrollIntoView({ behavior: 'smooth', block: 'center' });

        // Find readable label name
        let labelText = '';
        if (field.id) {
          const lbl = document.querySelector(`label[for="${field.id}"]`);
          if (lbl) labelText = lbl.innerText;
        }
        if (!labelText) {
          const parentLbl = field.closest('div, td')?.querySelector('label, span, strong');
          if (parentLbl) labelText = parentLbl.innerText;
        }
        labelText = (labelText || field.placeholder || field.name || 'Highlighted field').replace(/\*/g, '').trim();

        showBanner(`Missing field: "${labelText}". Please fill or select this first.`);
        if (window.AndroidBridge && window.AndroidBridge.vibrateAlert) {
          window.AndroidBridge.vibrateAlert();
        }
        break;
      }
    }
  }, true);

  // Re-apply fixes when dynamic AJAX/Angular/React DOM updates occur
  const observer = new MutationObserver(() => fixDropdownInputs());
  observer.observe(document.body, { childList: true, subtree: true });
  fixDropdownInputs();
})();
