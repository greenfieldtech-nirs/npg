import { ChangeEvent, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Stack } from '@mui/material'
import CloudDownloadIcon from '@mui/icons-material/CloudDownload'
import UploadFileIcon from '@mui/icons-material/UploadFile'
import { apiErrorMessage, numberingPlanApi } from '../../api/client'
import { NumberingPlanImportResult } from '../../api/types'
import { useAuth } from '../../auth/AuthContext'

/** ADMIN controls that replace the stored plan: fetch it from data.gov.il, or upload a downloaded copy. */
export default function NumberingPlanImport() {
  const { hasRole } = useAuth()
  const queryClient = useQueryClient()
  const fileInput = useRef<HTMLInputElement>(null)
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<NumberingPlanImportResult | null>(null)
  const [error, setError] = useState<string | null>(null)

  if (!hasRole('ADMIN')) return null

  const run = async (importing: () => Promise<NumberingPlanImportResult>) => {
    setBusy(true)
    setError(null)
    setResult(null)
    try {
      setResult(await importing())
      await queryClient.invalidateQueries({ queryKey: ['numberingPlan'] })
    } catch (err) {
      setError(apiErrorMessage(err, 'Import failed'))
    } finally {
      setBusy(false)
    }
  }

  const onFile = (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    e.target.value = ''
    if (file) run(async () => numberingPlanApi.upload(await file.text()))
  }

  return (
    <Stack spacing={1.5}>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
        <Button variant="contained" size="small" startIcon={<CloudDownloadIcon />} disabled={busy}
          onClick={() => run(numberingPlanApi.importOfficial)}>
          Refresh from data.gov.il
        </Button>
        <Button variant="outlined" size="small" startIcon={<UploadFileIcon />} disabled={busy}
          onClick={() => fileInput.current?.click()}>
          Upload CSV
        </Button>
        <input ref={fileInput} type="file" accept=".csv,text/csv" hidden onChange={onFile} />
      </Stack>
      {error && <Alert severity="error" onClose={() => setError(null)}>{error}</Alert>}
      {result && (
        <Alert severity={result.warnings.length ? 'warning' : 'success'} onClose={() => setResult(null)}>
          Imported {result.ranges} ranges for {result.operators} operators.
          {result.warnings.length > 0 && ` ${result.warnings.length} row(s) skipped: ${result.warnings.slice(0, 3).join('; ')}`}
        </Alert>
      )}
    </Stack>
  )
}
