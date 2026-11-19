export default defineNuxtPlugin(async () => {
  const { restoreSession } = useApi()
  await restoreSession()
})
