// Mocha's 2 s default times out heavy browser test suites; match long-running-tests.js timeout.
config.client = config.client || {};
config.client.mocha = Object.assign({}, config.client.mocha, { timeout: 1200000 });
