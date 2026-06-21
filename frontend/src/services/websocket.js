import SockJS from 'sockjs-client'

class WebSocketService {
  constructor() {
    this.socket = null
    this.listeners = new Map()
    this.binaryDecoder = new BinaryMessageDecoder()
    this.isConnected = false
    this.reconnectAttempts = 0
    this.maxReconnectAttempts = 5
    this.reconnectDelay = 1000
  }

  connect(url) {
    return new Promise((resolve, reject) => {
      try {
        this.socket = new SockJS(url)

        this.socket.onopen = () => {
          console.log('[WebSocket] Connection established')
          this.isConnected = true
          this.reconnectAttempts = 0
          this.emit('connected', {})
          resolve()
        }

        this.socket.onmessage = (event) => {
          if (event.data instanceof ArrayBuffer || event.data instanceof Blob) {
            this.handleBinaryMessage(event.data)
          } else {
            try {
              const message = JSON.parse(event.data)
              this.emit('textMessage', message)
              this.emit(message.type, message.data)
            } catch (e) {
              console.warn('[WebSocket] Failed to parse text message:', e)
            }
          }
        }

        this.socket.onclose = () => {
          console.log('[WebSocket] Connection closed')
          this.isConnected = false
          this.emit('disconnected', {})
          this.attemptReconnect(url)
        }

        this.socket.onerror = (error) => {
          console.error('[WebSocket] Error:', error)
          this.emit('error', error)
          reject(error)
        }
      } catch (e) {
        reject(e)
      }
    })
  }

  attemptReconnect(url) {
    if (this.reconnectAttempts >= this.maxReconnectAttempts) {
      console.error('[WebSocket] Max reconnection attempts reached')
      this.emit('reconnectFailed', {})
      return
    }

    this.reconnectAttempts++
    const delay = this.reconnectDelay * Math.pow(2, this.reconnectAttempts - 1)
    console.log(`[WebSocket] Reconnecting in ${delay}ms (attempt ${this.reconnectAttempts}/${this.maxReconnectAttempts})`)

    setTimeout(() => {
      this.connect(url).catch(() => {
        console.warn('[WebSocket] Reconnection attempt failed')
      })
    }, delay)
  }

  async handleBinaryMessage(data) {
    try {
      const arrayBuffer = data instanceof Blob ? await data.arrayBuffer() : data
      const decoded = this.binaryDecoder.decode(new Uint8Array(arrayBuffer))

      if (decoded) {
        if (decoded.messageType === 0 || decoded.messageType === 1) {
          this.emit('sliceData', decoded)
        } else if (decoded.messageType === 3) {
          this.emit('endOfStream', decoded)
        } else if (decoded.messageType === 4) {
          this.emit('streamError', decoded)
        }
      }
    } catch (e) {
      console.error('[WebSocket] Failed to decode binary message:', e)
    }
  }

  send(action, payload = {}) {
    if (!this.isConnected || !this.socket) {
      throw new Error('WebSocket not connected')
    }

    const message = JSON.stringify({ action, ...payload })
    this.socket.send(message)
  }

  startStream(startIndex, endIndex) {
    this.send('START_STREAM', { startIndex, endIndex })
  }

  stopStream() {
    this.send('STOP_STREAM')
  }

  requestSlice(sliceIndex) {
    this.send('REQUEST_SLICE', { sliceIndex })
  }

  requestRange(startIndex, endIndex) {
    this.send('REQUEST_RANGE', { startIndex, endIndex })
  }

  setIsodoseLevels(levels) {
    this.send('SET_ISODOSE_LEVELS', { levels })
  }

  getStatus() {
    this.send('GET_STATUS')
  }

  on(event, callback) {
    if (!this.listeners.has(event)) {
      this.listeners.set(event, new Set())
    }
    this.listeners.get(event).add(callback)
    return () => this.off(event, callback)
  }

  off(event, callback) {
    const callbacks = this.listeners.get(event)
    if (callbacks) {
      callbacks.delete(callback)
    }
  }

  emit(event, data) {
    const callbacks = this.listeners.get(event)
    if (callbacks) {
      callbacks.forEach(callback => {
        try {
          callback(data)
        } catch (e) {
          console.error(`[WebSocket] Error in ${event} listener:`, e)
        }
      })
    }
  }

  disconnect() {
    if (this.socket) {
      this.socket.close()
      this.socket = null
    }
    this.isConnected = false
  }
}

class BinaryMessageDecoder {
  decode(bytes) {
    if (bytes.length < 8 || bytes[0] !== 0xAA || bytes[bytes.length - 1] !== 0x55) {
      console.warn('[BinaryDecoder] Invalid message header or footer')
      return null
    }

    const view = new DataView(bytes.buffer, bytes.byteOffset)
    let offset = 2

    const version = bytes[1]
    const messageType = bytes[2]
    const isodoseLevelCount = bytes[3]
    offset += 2

    const sequenceNumber = view.getBigUint64(offset, false)
    offset += 8

    const timestampMs = view.getBigUint64(offset, false)
    offset += 8

    const sliceIndex = view.getInt32(offset, false)
    offset += 4

    const totalSlices = view.getInt32(offset, false)
    offset += 4

    const columns = view.getInt32(offset, false)
    offset += 4

    const rows = view.getInt32(offset, false)
    offset += 4

    const sliceZPosition = view.getFloat64(offset, false)
    offset += 8

    const pixelSpacingX = view.getFloat64(offset, false)
    offset += 8

    const pixelSpacingY = view.getFloat64(offset, false)
    offset += 8

    const doseGridScaling = view.getFloat64(offset, false)
    offset += 8

    const doseMaximum = view.getFloat64(offset, false)
    offset += 8

    const doseMinimum = view.getFloat64(offset, false)
    offset += 8

    const filteredCalibrationValue = view.getFloat64(offset, false)
    offset += 8

    const calibrationPulseTimestamp = view.getBigUint64(offset, false)
    offset += 8

    const isodoseLevels = []
    for (let i = 0; i < isodoseLevelCount; i++) {
      isodoseLevels.push(view.getFloat64(offset, false))
      offset += 8
    }

    const dataLength = view.getInt32(offset, false)
    offset += 4

    let doseData = null
    if (dataLength > 0 && offset + dataLength * 4 < bytes.length) {
      doseData = new Float32Array(dataLength)
      const doseView = new DataView(bytes.buffer, bytes.byteOffset + offset, dataLength * 4)
      for (let i = 0; i < dataLength; i++) {
        doseData[i] = doseView.getFloat32(i * 4, true)
      }
    }

    return {
      version,
      messageType,
      sequenceNumber: Number(sequenceNumber),
      timestamp: new Date(Number(timestampMs)),
      sliceIndex,
      totalSlices,
      columns,
      rows,
      sliceZPosition,
      pixelSpacingX,
      pixelSpacingY,
      doseGridScaling,
      doseMaximum,
      doseMinimum,
      filteredCalibrationValue,
      calibrationPulseTimestamp: Number(calibrationPulseTimestamp),
      isodoseLevels,
      doseData
    }
  }
}

export const websocketService = new WebSocketService()
export default websocketService
