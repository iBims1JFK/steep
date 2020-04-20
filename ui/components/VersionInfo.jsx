import "./VersionInfo.scss"
import Alert from "./Alert"
import DefinitionList from "./DefinitionList"
import DefinitionListItem from "./DefinitionListItem"
import { useEffect, useState } from "react"
import fetcher from "./lib/json-fetcher"

export default () => {
  const [data, setData] = useState()
  const [error, setError] = useState()

  useEffect(() => {
    fetcher(process.env.baseUrl)
      .then(setData)
      .catch(err => {
        console.log(err)
        setError(<Alert error>Could not load version information</Alert>)
      })
  }, [])

  if (typeof error !== "undefined") {
    return error
  } else if (typeof data === "undefined") {
    return (
      <></>
    )
  } else {
    let options = {
      day: "numeric",
      month: "long",
      year: "numeric",
      hour: "numeric",
      hour12: false,
      minute: "numeric",
      second: "numeric",
      timeZoneName: "short"
    }
    let timestamp = new Intl.DateTimeFormat("en-GB", options)
      .format(new Date(data.timestamp))
    return (
      <DefinitionList>
        <DefinitionListItem title="Version">{data.version}</DefinitionListItem>
        <DefinitionListItem title="Build">{data.build}</DefinitionListItem>
        <DefinitionListItem title="Commit">{data.commit}</DefinitionListItem>
        <DefinitionListItem title="Timestamp">{timestamp}</DefinitionListItem>
      </DefinitionList>
    )
  }
}
