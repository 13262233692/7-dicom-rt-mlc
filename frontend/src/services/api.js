import axios from 'axios'

const api = axios.create({
  baseURL: '/rt-verification/api',
  timeout: 300000
})

export const uploadRtDose = (file, onProgress) => {
  const formData = new FormData()
  formData.append('file', file)
  return api.post('/upload/rtdose', formData, {
    onUploadProgress: onProgress
  })
}

export const uploadRtStructure = (file, onProgress) => {
  const formData = new FormData()
  formData.append('file', file)
  return api.post('/upload/rtstructure', formData, {
    onUploadProgress: onProgress
  })
}

export const sendCalibrationPulse = (rawValue) => {
  return api.post('/calibration/pulse', {
    rawValue,
    timestamp: Date.now(),
    sourceDevice: 'SIMULATED_ION_CHAMBER'
  })
}

export const getCalibrationStatistics = () => {
  return api.get('/calibration/statistics')
}

export const resetCalibration = () => {
  return api.post('/calibration/reset')
}

export const getSystemStatus = () => {
  return api.get('/status')
}

export const setIsodoseLevels = (levels) => {
  return api.post('/isodose/levels', { levels })
}

export default api
