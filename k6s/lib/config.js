// Base URLs: inside compose they are the service names, from the host use localhost.
export const ORDER_URL = __ENV.ORDER_URL || 'http://localhost:8080';
export const INVENTORY_URL = __ENV.INVENTORY_URL || 'http://localhost:8081';

export const SKUS = ['SKU-BOOK', 'SKU-MUG', 'SKU-PHONE', 'SKU-LAPTOP'];
export const CUSTOMERS = ['alice', 'bob', 'gold-carol', 'vip-dave'];

// Thresholds shared by every test type. "expected" failures (402/404/409/400 on purpose)
// do not count towards http_req_failed, see expectedStatuses() in api.js.
export const thresholds = {
  http_req_failed: ['rate<0.01'],
  checks: ['rate>0.99'],
  'http_req_duration{group:::Place order}': ['p(95)<800'],
  'http_req_duration{group:::Read orders}': ['p(95)<300'],
  'http_req_duration{group:::Inventory}': ['p(95)<300'],
  'http_req_duration{group:::Actuator}': ['p(95)<300'],
  'http_req_duration{group:::Failure scenarios}': ['p(95)<800'],
};
