const http = require('http')

const port = Number(process.env.MOCK_AI_PORT || 18080)

function assistant(content, toolCalls) {
  const message = { role: 'assistant', content }
  if (toolCalls) {
    message.reasoning_content = 'mock tool reasoning'
    message.tool_calls = toolCalls
  }
  return { choices: [{ message }] }
}

function toolCall(id, name, args = {}) {
  return {
    id,
    type: 'function',
    function: { name, arguments: JSON.stringify(args) },
  }
}

function readToolData(message) {
  try {
    return JSON.parse(message.content || '{}')
  } catch {
    return { success: false }
  }
}

const server = http.createServer((request, response) => {
  if (request.method !== 'POST' || request.url !== '/v1/chat/completions') {
    response.writeHead(404)
    response.end()
    return
  }

  let body = ''
  request.setEncoding('utf8')
  request.on('data', (chunk) => {
    body += chunk
  })
  request.on('end', () => {
    try {
      const payload = JSON.parse(body)
      const systemPrompt = payload.messages?.[0]?.content || ''
      const userMessage = [...(payload.messages || [])]
        .reverse()
        .find((message) => message.role === 'user')?.content || ''
      const toolMessages = (payload.messages || []).filter(
        (message) => message.role === 'tool'
      )

      let result
      if (payload.tools && userMessage.includes('AGENT_TOOL_TEST')) {
        const lastTool = toolMessages.at(-1)
        if (!lastTool) {
          result = assistant(null, [toolCall('call-user', 'get_current_user')])
        } else if (lastTool.name === 'get_current_user') {
          result = assistant(null, [
            toolCall('call-storage', 'list_storage_sources'),
          ])
        } else if (lastTool.name === 'list_storage_sources') {
          const storageResult = readToolData(lastTool)
          const storageKey = storageResult.data?.storageSources?.[0]?.key
          result = storageKey
            ? assistant(null, [
                toolCall('call-directory', 'list_directory', {
                  storageKey,
                  path: '/',
                  orderBy: 'name',
                  orderDirection: 'asc',
                }),
              ])
            : assistant('AGENT_TOOL_OK 当前账号没有可用存储源')
        } else {
          const user = readToolData(toolMessages[0]).data || {}
          const sources = readToolData(toolMessages[1]).data || {}
          const directory = readToolData(toolMessages[2]).data || {}
          const reasoningPreserved = (payload.messages || [])
            .filter((message) => message.role === 'assistant' && message.tool_calls)
            .every((message) => message.reasoning_content === 'mock tool reasoning')
          result = assistant(
            `AGENT_TOOL_OK 用户=${user.username} 角色=${user.role} ` +
              `存储源=${sources.count} 根目录项目=${directory.total} ` +
              `reasoning=${reasoningPreserved}`
          )
        }
      } else {
        const checks = {
          tools: Array.isArray(payload.tools) && payload.tools.length === 3,
          userKnowledge: systemPrompt.includes('平台用户知识库'),
          knowledgeSection: systemPrompt.includes('【知识库：'),
          pageContext: systemPrompt.includes('用户当前所在页面路径：/'),
          adminLeak: systemPrompt.includes('后台管理员知识库'),
        }
        result = assistant(`MOCK_AI_OK ${JSON.stringify(checks)}`)
      }

      response.writeHead(200, {
        'Content-Type': 'application/json; charset=utf-8',
      })
      response.end(JSON.stringify(result))
    } catch (error) {
      response.writeHead(400, {
        'Content-Type': 'application/json; charset=utf-8',
      })
      response.end(JSON.stringify({ error: { message: error.message } }))
    }
  })
})

server.listen(port, '127.0.0.1', () => {
  console.log(`Mock OpenAI server listening on http://127.0.0.1:${port}/v1`)
})
