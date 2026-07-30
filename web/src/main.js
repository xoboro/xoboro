import { mount } from 'svelte'
import App from './App.svelte'
import { setupI18n } from './lib/i18n.js'
import './styles/global.css'

setupI18n({ navigatorLanguages: navigator.languages ?? [navigator.language] })

export default mount(App, { target: document.getElementById('app') })
