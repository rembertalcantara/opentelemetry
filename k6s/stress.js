// Beyond normal load: step up until something degrades. Thresholds abort the run when latency explodes.
import { thresholds } from './lib/config.js';
import { userJourney } from './lib/api.js';

export const options = {
  stages: [
    { duration: '1m', target: 20 },
    { duration: '1m', target: 50 },
    { duration: '1m', target: 100 },
    { duration: '1m', target: 150 },
    { duration: '1m', target: 0 },
  ],
  thresholds: {
    ...thresholds,
    http_req_failed: [{ threshold: 'rate<0.05', abortOnFail: true, delayAbortEval: '30s' }],
  },
};

export default userJourney;
