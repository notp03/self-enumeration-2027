(function () {
  if (window.__censusEnhancerLoaded) return;
  window.__censusEnhancerLoaded = true;

  // --- 1. PARENT-FRIENDLY CSS & PROGRESS BAR ---
  const style = document.createElement('style');
  style.innerHTML = `
    body { padding-top: 38px !important; }
    body, input, select, button, label {
      font-size: 16px !important;
      line-height: 1.5 !important;
    }
    input[type="radio"], input[type="checkbox"] {
      width: 24px !important;
      height: 24px !important;
      margin-right: 10px !important;
      vertical-align: middle !important;
    }
    button, .btn, input[type="submit"], input[type="button"] {
      min-height: 48px !important;
      padding: 10px 18px !important;
      font-weight: 600 !important;
      border-radius: 8px !important;
    }
    .census-field-error {
      border: 3px solid #d93025 !important;
      background-color: #fff5f5 !important;
      box-shadow: 0 0 10px rgba(217, 48, 37, 0.45) !important;
    }
    #census-progress-wrap {
      position: fixed;
      top: 0; left: 0; right: 0;
      height: 34px;
      background: #1a237e;
      color: #fff;
      font-size: 14px;
      font-weight: 600;
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 0 12px;
      z-index: 999990;
      box-shadow: 0 2px 6px rgba(0,0,0,0.25);
    }
    #census-progress-bar-bg {
      flex: 1;
      height: 10px;
      background: rgba(255,255,255,0.25);
      border-radius: 5px;
      margin: 0 10px;
      overflow: hidden;
    }
    #census-progress-bar-fill {
      height: 100%;
      width: 0%;
      background: #4caf50;
      transition: width 0.25s ease;
    }
    #census-alert-banner {
      position: fixed;
      top: 36px; left: 8px; right: 8px;
      background: #d93025;
      color: #fff;
      padding: 14px 16px;
      border-radius: 8px;
      font-size: 15px;
      font-weight: bold;
      z-index: 999999;
      box-shadow: 0 4px 12px rgba(0,0,0,0.35);
      display: none;
    }
  `;
  document.head.appendChild(style);

  // Sticky Progress Bar
  const progressWrap = document.createElement('div');
  progressWrap.id = 'census-progress-wrap';
  progressWrap.innerHTML = `
    <span id="census-prog-text">Page Progress: 0%</span>
    <div id="census-progress-bar-bg"><div id="census-progress-bar-fill"></div></div>
    <span id="census-save-dot" style="font-size:12px;color:#a5d6a7;">Auto-Save ON</span>
  `;
  document.body.appendChild(progressWrap);

  // Alert Banner
  const banner = document.createElement('div');
  banner.id = 'census-alert-banner';
  banner.onclick = () => { banner.style.display = 'none'; };
  document.body.appendChild(banner);

  function showBanner(msg, color = '#d93025') {
    banner.style.background = color;
    banner.textContent = msg + ' (Tap to close)';
    banner.style.display = 'block';
  }

  // --- 2. FIELD KEY HELPER & AUTO-SAVE ENGINE ---
  function getFieldKey(el, idx) {
    return el.id || el.name || el.getAttribute('formcontrolname') || el.placeholder || ('field_' + idx);
  }

  function getVisibleFields() {
    return Array.from(document.querySelectorAll('input, select, textarea')).filter(el => {
      if (el.type === 'hidden' || el.disabled || el.offsetParent === null) return false;
      if (['button', 'submit', 'reset', 'file'].includes(el.type)) return false;
      return true;
    });
  }

  function updateProgressAndSave() {
    const fields = getVisibleFields();
    const draft = JSON.parse(localStorage.getItem('census_draft_v1') || '{}');
    let filledCount = 0;
    const seenRadioGroups = new Set();
    let totalLogicalFields = 0;

    fields.forEach((el, idx) => {
      const key = getFieldKey(el, idx);
      const isOtpOrCaptcha = /otp|captcha/i.test(key + ' ' + (el.placeholder || ''));

      if (el.type === 'radio') {
        if (!seenRadioGroups.has(el.name)) {
          seenRadioGroups.add(el.name);
          totalLogicalFields++;
          const checked = document.querySelector(`input[type="radio"][name="${el.name}"]:checked`);
          if (checked) {
            filledCount++;
            draft['radio_' + el.name] = checked.value;
          }
        }
        return;
      }

      totalLogicalFields++;
      if (el.type === 'checkbox') {
        if (el.checked) filledCount++;
        draft['chk_' + key] = el.checked;
      } else {
        const val = (el.value || '').trim();
        const isDefaultSelect = el.tagName === 'SELECT' && (val === '' || val === '0' || val.toLowerCase().includes('select'));
        if (val !== '' && !isDefaultSelect) {
          filledCount++;
          if (!isOtpOrCaptcha) draft[key] = val;
        }
      }
    });

    const pct = totalLogicalFields > 0 ? Math.round((filledCount / totalLogicalFields) * 100) : 0;
    document.getElementById('census-prog-text').textContent = `Filled: ${filledCount}/${totalLogicalFields} (${pct}%)`;
    document.getElementById('census-progress-bar-fill').style.width = pct + '%';

    const jsonStr = JSON.stringify(draft);
    localStorage.setItem('census_draft_v1', jsonStr);
    if (window.AndroidBridge && window.AndroidBridge.saveDraft) {
      window.AndroidBridge.saveDraft(jsonStr);
    }
  }

  // Triggered from Native Toolbar "Restore" button
  window.__restoreCensusDraft = function (nativeJson) {
    const raw = nativeJson || localStorage.getItem('census_draft_v1');
    if (!raw) {
      showBanner('No saved draft found yet.', '#ef6c00');
      return;
    }
    const draft = JSON.parse(raw);
    let restored = 0;

    getVisibleFields().forEach((el, idx) => {
      const key = getFieldKey(el, idx);
      if (el.type === 'radio' && draft['radio_' + el.name] === el.value) {
        el.checked = true;
        el.dispatchEvent(new Event('change', { bubbles: true }));
        restored++;
      } else if (el.type === 'checkbox' && typeof draft['chk_' + key] === 'boolean') {
        el.checked = draft['chk_' + key];
        el.dispatchEvent(new Event('change', { bubbles: true }));
        restored++;
      } else if (draft[key] && !el.value) {
        el.value = draft[key];
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
        restored++;
      }
    });

    updateProgressAndSave();
    showBanner(`Restored ${restored} saved fields! Please verify dropdowns.`, '#2e7d32');
  };

  // --- 3. AUTO-FILL OTP FROM ANDROID SMS USER CONSENT ---
  window.__fillCensusOtp = function (otpCode) {
    const inputs = getVisibleFields();
    const otpInput = inputs.find(el =>
      /otp/i.test(el.id + ' ' + el.name + ' ' + (el.placeholder || '')) ||
      el.maxLength === 6 ||
      el.getAttribute('autocomplete') === 'one-time-code'
    );
    if (otpInput) {
      otpInput.value = otpCode;
      otpInput.dispatchEvent(new Event('input', { bubbles: true }));
      otpInput.dispatchEvent(new Event('change', { bubbles: true }));
      showBanner(`OTP ${otpCode} auto-filled! Tap Verify.`, '#2e7d32');
    } else {
      showBanner(`OTP received: ${otpCode} (Copied—paste into OTP box)`, '#1565c0');
    }
  };

  // --- 4. SUPPRESS KEYBOARD ON DROPDOWNS & DETECT EMPTY LISTS ---
  function fixDropdownInputs() {
    document.querySelectorAll('input[type="text"], input:not([type])').forEach(inp => {
      const ph = (inp.placeholder || '').toLowerCase();
      const cls = (inp.className || '').toLowerCase();
      const role = (inp.getAttribute('role') || '').toLowerCase();
      if (inp.readOnly || ph.includes('select') || cls.includes('select') || cls.includes('dropdown') || role === 'combobox') {
        inp.setAttribute('inputmode', 'none');
      }
    });
  }

  // --- 5. SE ID AUTO-VAULT DETECTOR (P + 10 DIGITS OR H + 10 DIGITS) ---
  function scanForSeId() {
    const text = document.body.innerText || '';
    const match = text.match(/\b[PH]\d{10}\b/);
    if (match && window.AndroidBridge && window.AndroidBridge.saveSeId) {
      window.AndroidBridge.saveSeId(match[0]);
    }
  }

  // --- 6. INTERCEPT PROCEED, RESEND OTP & MISSING FIELDS ---
  let lastResendTime = 0;

  document.addEventListener('click', function (e) {
    const btn = e.target.closest('button, input[type="submit"], input[type="button"], a.btn');
    if (!btn) return;
    const btnText = (btn.innerText || btn.value || '').toLowerCase();

    // Resend OTP Lockout Guard
    if (btnText.includes('resend')) {
      const now = Date.now();
      if (now - lastResendTime < 90000) {
        const waitSec = Math.ceil((90000 - (now - lastResendTime)) / 1000);
        showBanner(`Wait ${waitSec}s before Resend! Too many OTP attempts lock the account for 30 mins.`, '#ef6c00');
        e.preventDefault();
        e.stopPropagation();
        return;
      }
      lastResendTime = now;
    }

    // Missing Field Scanner on Proceed / Submit / Save / Next
    if (btnText.includes('proceed') || btnText.includes('submit') || btnText.includes('save') || btnText.includes('next')) {
      document.querySelectorAll('.census-field-error').forEach(el => el.classList.remove('census-field-error'));
      banner.style.display = 'none';

      const fields = getVisibleFields();
      for (const field of fields) {
        const val = (field.value || '').trim();
        const isDefaultSelect = field.tagName === 'SELECT' && (val === '' || val === '0' || val.toLowerCase().includes('select'));
        const isRequired = field.required || field.getAttribute('aria-required') === 'true' || field.className.toLowerCase().includes('req');

        if ((isRequired || field.tagName === 'SELECT') && (val === '' || isDefaultSelect)) {
          field.classList.add('census-field-error');
          field.scrollIntoView({ behavior: 'smooth', block: 'center' });

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
    }
  }, true);

  document.addEventListener('input', updateProgressAndSave, true);
  document.addEventListener('change', updateProgressAndSave, true);

  const observer = new MutationObserver(() => {
    fixDropdownInputs();
    updateProgressAndSave();
    scanForSeId();
  });
  observer.observe(document.body, { childList: true, subtree: true });

  fixDropdownInputs();
  updateProgressAndSave();
  scanForSeId();
})();
