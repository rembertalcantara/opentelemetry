import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { ORDER_URL, INVENTORY_URL, SKUS, CUSTOMERS } from './config.js';

const JSON_HEADERS = { 'Content-Type': 'application/json' };

const pick = (list) => list[Math.floor(Math.random() * list.length)];

function params(name, ...expected) {
  return { headers: JSON_HEADERS, tags: { name }, responseCallback: http.expectedStatuses(...expected) };
}

function postOrder(body, name, ...expected) {
  return http.post(`${ORDER_URL}/orders`, JSON.stringify(body), params(name, ...expected));
}

// ---- order-service -------------------------------------------------------

/** POST /orders (201) then GET /orders/{id}. Out-of-stock (409) is valid: stock is finite and refilled every 20 s. */
export function placeAndReadOrder() {
  let orderId;
  group('Place order', () => {
    const sku = pick(SKUS);
    const res = postOrder(
      { sku, quantity: 1 + Math.floor(Math.random() * 3), customerId: pick(CUSTOMERS) },
      'POST /orders', 201, 409);
    const stockOut = res.status === 409;
    check(res, {
      'order created (or out of stock)': (r) => r.status === 201 || stockOut,
      'X-Trace-Id header': (r) => !!r.headers['X-Trace-Id'],
    });
    if (res.status === 201) orderId = res.json('id');
  });
  if (orderId) {
    group('Read orders', () => {
      const res = http.get(`${ORDER_URL}/orders/${orderId}`, params('GET /orders/{id}', 200));
      check(res, { 'order found': (r) => r.status === 200 });
    });
  }
}

export function listOrders() {
  group('Read orders', () => {
    const res = http.get(`${ORDER_URL}/orders`, params('GET /orders', 200));
    check(res, { 'orders listed': (r) => r.status === 200 });
  });
}

/** Documented error paths, each must answer with its documented status. One random case, or all of them. */
export function failureScenarios(all = false) {
  group('Failure scenarios', () => {
    const cases = [
      // The stock is reserved before payment, so under heavy load this can be 409 instead of 402.
      ['payment declined', { sku: 'SKU-BOOK', quantity: 1, customerId: 'fraudster-k6' }, 402, 409],
      ['unknown sku', { sku: 'SKU-UNKNOWN', quantity: 1, customerId: 'alice' }, 404],
      ['out of stock', { sku: 'SKU-LAPTOP', quantity: 100, customerId: 'alice' }, 409],
      ['blank sku', { sku: '', quantity: 1, customerId: 'alice' }, 400],
      ['quantity 0', { sku: 'SKU-BOOK', quantity: 0, customerId: 'alice' }, 400],
      ['quantity > 100', { sku: 'SKU-BOOK', quantity: 101, customerId: 'alice' }, 400],
    ];
    for (const [label, body, ...statuses] of all ? cases : [pick(cases)]) {
      const res = postOrder(body, `POST /orders (${label})`, ...statuses);
      check(res, { [`${label} -> ${statuses.join(' or ')}`]: (r) => statuses.includes(r.status) });
    }
    const missing = http.get(`${ORDER_URL}/orders/does-not-exist`, params('GET /orders/{id} (missing)', 404));
    check(missing, { 'missing order -> 404': (r) => r.status === 404 });
  });
}

// ---- inventory-service ---------------------------------------------------

export function inventory() {
  group('Inventory', () => {
    const sku = pick(SKUS);
    let res = http.get(`${INVENTORY_URL}/inventory`, params('GET /inventory', 200));
    check(res, { 'levels listed': (r) => r.status === 200 });

    res = http.get(`${INVENTORY_URL}/inventory/${sku}`, params('GET /inventory/{sku}', 200));
    check(res, { 'level returned': (r) => r.status === 200 && r.json('sku') === sku });

    // 409 is valid: the restock job only refills every 20 s.
    res = http.post(`${INVENTORY_URL}/inventory/${sku}/reservations?quantity=1`, null,
      params('POST /inventory/{sku}/reservations', 201, 409));
    check(res, { 'reservation accepted or stock exhausted': (r) => r.status === 201 || r.status === 409 });

    res = http.post(`${INVENTORY_URL}/inventory/SKU-UNKNOWN/reservations?quantity=1`, null,
      params('POST /inventory/{sku}/reservations (unknown)', 404));
    check(res, { 'unknown sku -> 404': (r) => r.status === 404 });
  });
}

// ---- actuator (both services) ---------------------------------------------

export function actuator() {
  group('Actuator', () => {
    for (const [svc, base, metric] of [
      ['order', ORDER_URL, 'orders.placed'],
      ['inventory', INVENTORY_URL, 'inventory.stock.level'],
    ]) {
      for (const path of ['/actuator/health', '/actuator/info', '/actuator/metrics', `/actuator/metrics/${metric}`]) {
        const res = http.get(`${base}${path}`, params(`GET ${svc} ${path}`, 200));
        check(res, { [`${svc} ${path} 200`]: (r) => r.status === 200 });
      }
    }
  });
}

/** One user iteration across all endpoints, weighted towards the order flow. */
export function userJourney() {
  placeAndReadOrder();
  sleep(0.2);
  const dice = Math.random();
  if (dice < 0.30) listOrders();
  else if (dice < 0.60) inventory();
  else if (dice < 0.75) failureScenarios();
  else if (dice < 0.85) actuator();
  sleep(0.3);
}
