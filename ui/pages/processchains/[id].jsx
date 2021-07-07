import DetailPage from "../../components/layouts/DetailPage"
import Link from "next/link"
import { useRouter } from "next/router"
import { useContext, useEffect, useState } from "react"
import Alert from "../../components/Alert"
import CancelModal from "../../components/CancelModal"
import CollapseButton from "../../components/CollapseButton"
import CodeBox from "../../components/CodeBox"
import DefinitionList from "../../components/DefinitionList"
import DefinitionListItem from "../../components/DefinitionListItem"
import EventBusContext from "../../components/lib/EventBusContext"
import { LOGS_PROCESSCHAINS_PREFIX } from "../../components/lib/EventBusMessages"
import Label from "../../components/Label"
import ListItemProgressBox from "../../components/ListItemProgressBox"
import LiveDuration from "../../components/LiveDuration"
import ProcessChainContext from "../../components/processchains/ProcessChainContext"
import ProcessChainLog from "../../components/ProcessChainLog"
import Tooltip from "../../components/Tooltip"
import { formatDate, formatDurationTitle } from "../../components/lib/date-time-utils"
import fetcher from "../../components/lib/json-fetcher"
import { disableBodyScroll, enableBodyScroll } from "body-scroll-lock"
import EventBus from "vertx3-eventbus-client"
import classNames from "classnames"
import styles from "./[id].scss"

function ProcessChainDetails({ id }) {
  const processChains = useContext(ProcessChainContext.Items)
  const updateProcessChains = useContext(ProcessChainContext.UpdateItems)
  const eventBus = useContext(EventBusContext)
  const [error, setError] = useState()
  const [cancelModalOpen, setCancelModalOpen] = useState()
  const [logAvailable, setLogAvailable] = useState(false)
  const [logCollapsed, setLogCollapsed] = useState()
  const [logError, setLogError] = useState()
  const [waitForLog, setWaitForLog] = useState(false)

  useEffect(() => {
    if (!id) {
      return
    }

    fetcher(`${process.env.baseUrl}/processchains/${id}`)
      .then(pc => updateProcessChains({ action: "set", items: [pc] }))
      .catch(err => {
        console.log(err)
        setError(<Alert error>Could not load process chain</Alert>)
      })

    // check if a log file is available
    fetcher(`${process.env.baseUrl}/logs/processchains/${id}`, false, {
      method: "HEAD"
    }).then(() => setLogAvailable(true))
      .catch(() => {
        setLogAvailable(false)
        setWaitForLog(true)
      })
  }, [id, updateProcessChains])

  useEffect(() => {
    let processChainLogConsumerAddress = LOGS_PROCESSCHAINS_PREFIX + id

    function doWaitForLog() {
      // register a handler that listens to log events, enables the log
      // section, and then unregisters itself
      if (eventBus !== undefined) {
        eventBus.registerHandler(processChainLogConsumerAddress, onNewLogLine)
      }
    }

    function onNewLogLine() {
      setLogAvailable(true)
      unregisterLogConsumer()
    }

    function unregisterLogConsumer() {
      if (eventBus !== undefined && eventBus.state === EventBus.OPEN) {
        eventBus.unregisterHandler(processChainLogConsumerAddress, onNewLogLine)
      }
    }

    if (waitForLog) {
      doWaitForLog()
    }

    return () => {
      unregisterLogConsumer()
    }
  }, [id, eventBus, waitForLog])

  function onCancel() {
    setCancelModalOpen(true)
  }

  function onDoCancel() {
    setCancelModalOpen(false)
    fetcher(`${process.env.baseUrl}/processchains/${id}`, false, {
      method: "PUT",
      body: JSON.stringify({
        status: "CANCELLED"
      })
    }).catch(error => {
      console.error(error)
    })
  }

  function onCancelModalOpen() {
    disableBodyScroll()
  }

  function onCancelModalClose() {
    enableBodyScroll()
  }

  let breadcrumbs
  let title
  let processchain
  let menu

  if (processChains.items !== undefined && processChains.items.length > 0) {
    let pc = processChains.items[0]
    title = pc.id
    breadcrumbs = [
      <Link href="/workflows" key="workflows"><a>Workflows</a></Link>,
      <Link href="/workflows/[id]" as={`/workflows/${pc.submissionId}`} key={pc.submissionId}>
        <a>{pc.submissionId}</a>
      </Link>,
      <Link href={{
        pathname: "/processchains",
        query: {
          submissionId: pc.submissionId
        }
      }} key="processchains">
        <a>Process chains</a>
      </Link>,
      pc.id
    ]

    let menuItems = []
    if (logAvailable) {
      menuItems.push(<a href={`${process.env.baseUrl}/logs/processchains/${id}?forceDownload=true`}
        key="download-log"><li>Download log</li></a>)
    }
    if (pc.status === "REGISTERED" || pc.status === "RUNNING") {
      menuItems.push(<li key="cancel" onClick={onCancel}>Cancel</li>)
    }
    if (menuItems.length > 0) {
      menu = <ul>{menuItems}</ul>
    }

    let reqcap
    if (pc.requiredCapabilities === undefined || pc.requiredCapabilities.length === 0) {
      reqcap = <>&ndash;</>
    } else {
      reqcap = pc.requiredCapabilities.map((r, i) => <Label key={i}>{r}</Label>)
    }

    let estimatedProgress
    if (pc.status === "RUNNING" && pc.estimatedProgress !== undefined && pc.estimatedProgress !== null) {
      estimatedProgress = (
        <Tooltip title="Estimated progress">
          {(pc.estimatedProgress * 100).toFixed()}&thinsp;%
        </Tooltip>
      )
    }

    let progress = {
      status: pc.status,
      subtitle: estimatedProgress
    }

    processchain = (<>
      <div className="detail-header">
        <div className="detail-header-left">
          <DefinitionList>
            <DefinitionListItem title="Start time">
              {pc.startTime ? formatDate(pc.startTime) : <>&ndash;</>}
            </DefinitionListItem>
            <DefinitionListItem title="End time">
              {pc.endTime ? formatDate(pc.endTime) : <>&ndash;</>}
            </DefinitionListItem>
            <DefinitionListItem title="Time elapsed">
              {
                pc.startTime && pc.endTime ? formatDurationTitle(pc.startTime, pc.endTime) : (
                  pc.startTime ? <LiveDuration startTime={pc.startTime} /> : <>&ndash;</>
                )
              }
            </DefinitionListItem>
            <DefinitionListItem title="Required capabilities">
              {reqcap}
            </DefinitionListItem>
          </DefinitionList>
        </div>
        <div className="detail-header-right">
          <ListItemProgressBox progress={progress} />
        </div>
      </div>
      {pc.errorMessage && (<>
        <h2>Error message</h2>
        <Alert error>{pc.errorMessage}</Alert>
      </>)}
      {logAvailable && (<>
        <h2><CollapseButton collapsed={logCollapsed}
          onCollapse={() => setLogCollapsed(!logCollapsed)}>Log</CollapseButton></h2>
        {logCollapsed && (
          <div className={classNames("log-wrapper", { error: logError !== undefined })}>
            <ProcessChainLog id={id} onError={setLogError} />
          </div>
        )}
      </>)}
      <h2>Executables</h2>
      <CodeBox json={pc.executables} />
      <style jsx>{styles}</style>
    </>)
  }

  return (
    <DetailPage breadcrumbs={breadcrumbs} title={title} menu={menu}>
      {processchain}
      {error}
      <CancelModal isOpen={cancelModalOpen} contentLabel="Cancel modal"
          onAfterOpen={onCancelModalOpen} onAfterClose={onCancelModalClose}
          onRequestClose={() => setCancelModalOpen(false)} title="Cancel process chain"
          onConfirm={onDoCancel} onDeny={() => setCancelModalOpen(false)}>
        <p>Are you sure you want to cancel this process chain?</p>
      </CancelModal>
    </DetailPage>
  )
}

const ProcessChain = () => {
  const router = useRouter()
  const { id } = router.query

  return (
    <ProcessChainContext.Provider pageSize={1} allowAdd={false}>
      <ProcessChainDetails id={id} />
    </ProcessChainContext.Provider>
  )
}

export default ProcessChain
