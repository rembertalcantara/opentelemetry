// Sudden surge: jump to 100 users in 10 s, then drop back.
import { thresholds } from './lib/config.js';
import { userJourney } from './lib/api.js';

export const options = {
  stages: [
    { duration: '20s', target: 5 },
    { duration: '10s', target: 100 },
    { duration: '1m', target: 100 },
    { duration: '10s', target: 5 },
    { duration: '30s', target: 5 },
    { duration: '10s', target: 0 },
  ],
  thresholds,
};

export default userJourney;
