import request from '@/utils/request'
import { updateConfig } from '@/api/config'
import { isEmpty } from 'lodash'

const PREFIX_STORAGE = '/repository/api/storage'
const PREFIX_STORAGE_CREDENTIALS = `${PREFIX_STORAGE}/credentials`
const STORAGE_CONFIG_PREFIX = 'storage'
const STORAGE_CACHE_CONFIG_PREFIX = 'cache'
const STORAGE_UPLOAD_CONFIG_PREFIX = 'upload'
const STORAGE_DOWNLOAD_CONFIG_PREFIX = 'download'
export const STORAGE_TYPE_FILESYSTEM = 'filesystem'
export const STORAGE_TYPE_INNER_COS = 'innercos'
export const STORAGE_TYPE_S3 = 's3'

export function credentials() {
  return request({
    url: `${PREFIX_STORAGE_CREDENTIALS}`,
    method: 'get'
  })
}

export function defaultCredential() {
  return request({
    url: `${PREFIX_STORAGE_CREDENTIALS}/default`,
    method: 'get'
  })
}

export function createCredential(credential) {
  normalizeCredential(credential)
  const createReq = {
    key: credential.key,
    credentials: credential,
    region: credential.region ? credential.region : ''
  }
  return request({
    url: `${PREFIX_STORAGE_CREDENTIALS}`,
    method: 'post',
    data: createReq
  })
}

export function updateCredential(key, credential, defaultCredential = false) {
  if (defaultCredential) {
    const prefix = `${STORAGE_CONFIG_PREFIX}.${credential.type}`
    const expireDaysKey = `${prefix}.${STORAGE_CACHE_CONFIG_PREFIX}.expireDays`
    const loadCacheFirstKey = `${prefix}.${STORAGE_CACHE_CONFIG_PREFIX}.loadCacheFirst`
    const values = [
      {
        'key': expireDaysKey,
        'value': credential.cache.expireDays
      },
      {
        'key': loadCacheFirstKey,
        'value': credential.cache.loadCacheFirst
      }
    ]
    if (credential.upload && !isEmpty(credential.upload.localPath)) {
      const uploadLocalPathConfigItem = {
        'key': `${STORAGE_CONFIG_PREFIX}.${credential.type}.${STORAGE_UPLOAD_CONFIG_PREFIX}.localPath`,
        'value': credential.upload.localPath
      }
      values.push(uploadLocalPathConfigItem)
    }
    if (credential.type === STORAGE_TYPE_INNER_COS) {
      values.push({
        'key': `${prefix}.slowLogSpeed`,
        'value': credential.slowLogSpeed
      })
      values.push({
        'key': `${prefix}.slowLogTimeInMillis`,
        'value': credential.slowLogTimeInMillis
      })
      values.push({
        'key': `${prefix}.${STORAGE_DOWNLOAD_CONFIG_PREFIX}.workers`,
        'value': credential.download.workers
      })
      values.push({
        'key': `${prefix}.${STORAGE_DOWNLOAD_CONFIG_PREFIX}.downloadTimeHighWaterMark`,
        'value': credential.download.downloadTimeHighWaterMark
      })
      values.push({
        'key': `${prefix}.${STORAGE_DOWNLOAD_CONFIG_PREFIX}.downloadTimeLowWaterMark`,
        'value': credential.download.downloadTimeLowWaterMark
      })
      values.push({
        'key': `${prefix}.${STORAGE_DOWNLOAD_CONFIG_PREFIX}.taskInterval`,
        'value': credential.download.taskInterval
      })
    }
    return updateConfig(values)
  } else {
    normalizeCredential(credential)
    const updateReq = {
      credentials: credential
    }
    return request({
      url: `${PREFIX_STORAGE_CREDENTIALS}/${key}`,
      method: 'put',
      data: updateReq
    })
  }
}

function normalizeCredential(credential) {
  if (credential.upload) {
    if (isEmpty(credential.upload.location)) {
      credential.upload.location = undefined
    }
    if (isEmpty(credential.upload.localPath)) {
      credential.upload.localPath = undefined
    }
  }
  return credential
}

export function deleteCredential(key) {
  return request({
    url: `${PREFIX_STORAGE_CREDENTIALS}/${key}`,
    method: 'delete'
  })
}
