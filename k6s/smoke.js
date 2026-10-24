// Sanity check: one user, one pass over every endpoint. Run it first.
import { sleep } from 'k6';
import { thresholds } from './lib/config.js';
import * as api from './lib/api.js';

export const options = {
  vus: 1,
  iterations: 3,
  thresholds,
};

export default function () {
  api.placeAndReadOrder();
  api.listOrders();
  api.failureScenarios(true);
  api.inventory();
  api.actuator();
  sleep(1);
}
