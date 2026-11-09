// Dash0 real-user monitoring: page views, Web Vitals, errors and a trace for every API call, which
// continues into the backend (askonce -> elasticsearch) through the W3C traceparent header.
// The SDK only loads when the server provides an ingest token (GET /api/rum-config).
(() => {
  const SDK_URL = 'https://cdn.jsdelivr.net/npm/@dash0/sdk-web@0.28.3/dist/dash0.iife.js';
  const SDK_INTEGRITY = 'sha384-BIFddutv+IejHs+PWuMjB4hwnb+88nlT8DBWoxHfzGAEqksjVjQkJYPt+7h5s6z0';

  // Queue calls until the SDK script has loaded.
  window.dash0 = window.dash0 || function () {
    (window.dash0._q = window.dash0._q || []).push(arguments);
  };

  fetch('/api/rum-config')
    .then((response) => (response.ok ? response.json() : null))
    .then((config) => {
      if (!config || !config.enabled) return;
      window.dash0('init', {
        serviceName: config.serviceName,
        environment: config.environment,
        endpoint: { url: config.endpoint, authToken: config.authToken },
      });
      const script = document.createElement('script');
      script.src = SDK_URL;
      script.integrity = SDK_INTEGRITY;
      script.crossOrigin = 'anonymous';
      script.async = true;
      document.head.append(script);
    })
    .catch(() => { /* RUM is optional; never break the page */ });
})();
