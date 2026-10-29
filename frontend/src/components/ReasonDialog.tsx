import { ReactNode, useState } from 'react'
import { Alert, Button, Dialog, DialogActions, DialogContent, DialogTitle, TextField, Typography } from '@mui/material'
import { apiErrorMessage } from '../api/client'

interface Props {
  open: boolean
  title: string
  /** What will happen, shown above the reason field. */
  description?: ReactNode
  confirmLabel?: string
  danger?: boolean
  /** Extra inputs (e.g. a date) rendered before the reason field. */
  children?: ReactNode
  reasonLabel?: string
  reasonRequired?: boolean
  onClose: () => void
  onConfirm: (reason: string) => Promise<unknown>
}

/** Every operator action is audited with a reason; this dialog collects it and shows server errors. */
export default function ReasonDialog({
  open, title, description, confirmLabel = 'Confirm', danger, children, reasonLabel = 'Reason (audited)',
  reasonRequired = true, onClose, onConfirm
}: Props) {
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const close = () => {
    setReason('')
    setError(null)
    onClose()
  }

  const confirm = async () => {
    setBusy(true)
    setError(null)
    try {
      await onConfirm(reason.trim())
      setReason('')
      onClose()
    } catch (err) {
      setError(apiErrorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open={open} onClose={close} maxWidth="sm" fullWidth PaperProps={{ sx: { borderRadius: '12px' } }}>
      <DialogTitle><Typography variant="h6" component="span">{title}</Typography></DialogTitle>
      <DialogContent sx={{ display: 'flex', flexDirection: 'column', gap: 2, pt: 1 }}>
        {error && <Alert severity="error">{error}</Alert>}
        {description && <Typography variant="body2" color="text.secondary">{description}</Typography>}
        {children}
        <TextField
          label={reasonLabel} fullWidth multiline minRows={2} autoFocus
          value={reason} onChange={e => setReason(e.target.value)}
        />
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={close} disabled={busy}>Cancel</Button>
        <Button
          variant="contained" color={danger ? 'error' : 'primary'}
          disabled={busy || (reasonRequired && !reason.trim())}
          onClick={confirm}
        >
          {confirmLabel}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
