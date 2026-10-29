import { useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Paper, Alert, Box } from '@mui/material'
import axios from 'axios'
import { messageApi } from '../api/client'
import PageHeader from '../components/PageHeader'
import Loader from '../components/Loader'
import XmlBlock from '../components/XmlBlock'

export default function XmlViewer() {
  const { uuid } = useParams()
  const { data, isLoading, error } = useQuery({
    queryKey: ['xml', uuid],
    queryFn: () => messageApi.getXml(uuid!),
    retry: false
  })

  return (
    <Box>
      <PageHeader title="Raw XML" subtitle={`Message ${uuid ?? ''}`} />
      <Paper sx={{ p: 2 }}>
        <Alert severity="warning" sx={{ mb: 2 }}>
          This XML access is audited. Do not share sensitive data.
        </Alert>
        {isLoading && <Loader />}
        {error && (
          <Alert severity="info">
            {axios.isAxiosError(error) && error.response?.status === 404
              ? 'This message has no stored XML document.'
              : (axios.isAxiosError(error) ? (error.response?.data?.error ?? error.message) : 'Failed to load XML')}
          </Alert>
        )}
        {data && <XmlBlock xml={data} />}
      </Paper>
    </Box>
  )
}
