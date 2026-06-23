import { definePreset } from '@primeuix/themes'
import Aura from '@primeuix/themes/aura'
import { semantic } from './semantic'
import { components } from './components'

export const MyPreset = definePreset(Aura, {
    semantic,
    components
})