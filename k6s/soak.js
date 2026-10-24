// Long, moderate load to spot leaks (orders are kept in memory; watch heap and GC on the Grafana dashboard).
import { thresholds } from './lib/config.js';
import { userJourney } from './lib/api.js';

export const options = {
  stages: [
    { duration: '1m', target: 15 },
    { duration: '30m', target: 15 },
    { duration: '1m', target: 0 },
  ],
  thresholds,
};

export default userJourney;
