// Average expected load: ramp to 20 users, hold, ramp down.
import { thresholds } from './lib/config.js';
import { userJourney } from './lib/api.js';

export const options = {
  stages: [
    { duration: '30s', target: 20 },
    { duration: '2m', target: 20 },
    { duration: '30s', target: 0 },
  ],
  thresholds,
};

export default userJourney;
